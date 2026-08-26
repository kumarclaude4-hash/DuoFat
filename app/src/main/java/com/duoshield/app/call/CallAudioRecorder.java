package com.duoshield.app.call;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import androidx.annotation.Nullable;

import org.webrtc.AudioTrackSink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Records a voice call to a self-contained {@code .m4a} (AAC-LC) file by tapping the two
 * PCM streams WebRTC already produces:
 *
 * <ul>
 *   <li><b>Local mic</b> — delivered frame-by-frame through the
 *       {@link org.webrtc.audio.JavaAudioDeviceModule} {@code SamplesReadyCallback}
 *       (post AEC/NS/AGC, i.e. exactly what the peer hears).</li>
 *   <li><b>Remote peer</b> — delivered through an {@link AudioTrackSink} attached to the
 *       remote {@link org.webrtc.AudioTrack}.</li>
 * </ul>
 *
 * <p>Both taps run on real-time WebRTC threads, so this class does <em>no</em> encoding on
 * them: each side only downmixes to mono, resamples to the master rate, and hands the
 * samples off (local via a bounded queue, remote via a small ring buffer). A dedicated
 * mixer thread pulls the local timeline as the clock, adds whatever remote audio is
 * available (silence-padding gaps), and feeds the mix to a single {@link MediaCodec} AAC
 * encoder muxed into MP4. Keeping the heavy work off the audio threads is essential —
 * blocking the mic thread would degrade the live call itself.
 *
 * <p>The local mic timeline is treated as the master clock deliberately: it is the one
 * stream guaranteed to arrive at a steady 10 ms cadence for the whole call, so anchoring
 * the file length to it keeps the recording the same duration as the call regardless of
 * remote jitter or brief drops.
 */
public final class CallAudioRecorder {

    private static final String TAG = "CallAudioRecorder";

    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int BIT_RATE = 64_000;
    /** Remote ring capacity in seconds — bounds memory if the remote outruns the mixer. */
    private static final int REMOTE_BUFFER_SECONDS = 2;

    private final String outputPath;

    private volatile boolean running = false;
    /** Master (recording) sample rate — locked to the first local mic frame's rate. */
    private volatile int masterRate = -1;

    // Local mic frames (mono, already at masterRate) awaiting the mixer.
    private final LinkedBlockingQueue<short[]> localQueue = new LinkedBlockingQueue<>(256);

    // Remote peer samples (mono, resampled to masterRate). Lazily sized once masterRate known.
    @Nullable private volatile RingBuffer remoteRing;

    private Thread mixerThread;

    // Encoder state — only ever touched from the mixer thread.
    @Nullable private MediaCodec encoder;
    @Nullable private MediaMuxer muxer;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private long totalSamplesEncoded = 0L;

    private final AudioTrackSink remoteSink = new RemoteSink();

    public CallAudioRecorder(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getOutputPath() { return outputPath; }

    /** The sink to attach to the remote {@link org.webrtc.AudioTrack}. */
    public AudioTrackSink getRemoteSink() { return remoteSink; }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) return;
        running = true;
        totalSamplesEncoded = 0L;
        mixerThread = new Thread(this::mixLoop, "CallAudioMixer");
        mixerThread.start();
        Log.d(TAG, "Recording started → " + outputPath);
    }

    /**
     * Stops recording and finalises the file. Blocks briefly for the mixer thread to drain
     * remaining audio and write the MP4 trailer. Returns {@code true} when a non-empty file
     * was produced.
     */
    public synchronized boolean stop() {
        if (!running) return muxerStarted;
        running = false;
        try {
            if (mixerThread != null) mixerThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mixerThread = null;
        Log.d(TAG, "Recording stopped, produced audio=" + muxerStarted);
        return muxerStarted;
    }

    // ── Local mic tap (called on the WebRTC audio-record thread) ────────────────

    /**
     * @param data       interleaved 16-bit little-endian PCM
     * @param sampleRate mic capture rate
     * @param channels   mic channel count
     */
    public void onLocalSamples(byte[] data, int sampleRate, int channels) {
        if (!running || data == null || data.length == 0 || channels <= 0) return;
        if (masterRate < 0) {
            masterRate = sampleRate;
            remoteRing = new RingBuffer(sampleRate * REMOTE_BUFFER_SECONDS);
        }
        short[] mono = toMonoShorts(data, channels);
        // Non-blocking: if the mixer has stalled we drop a frame rather than back-pressure
        // the real-time mic thread, which must never wait.
        localQueue.offer(mono);
    }

    // ── Remote peer tap ─────────────────────────────────────────────────────────

    private final class RemoteSink implements AudioTrackSink {
        @Override
        public void onData(ByteBuffer audioData, int bitsPerSample, int sampleRate,
                           int numberOfChannels, int numberOfFrames,
                           long absoluteCaptureTimestampMs) {
            RingBuffer ring = remoteRing;
            if (!running || ring == null || masterRate <= 0
                    || bitsPerSample != 16 || numberOfChannels <= 0 || numberOfFrames <= 0) {
                return;
            }
            short[] mono = toMonoShorts(audioData, numberOfFrames, numberOfChannels);
            short[] atMaster = resampleMono(mono, sampleRate, masterRate);
            ring.write(atMaster);
        }
    }

    // ── Mixer + encoder thread ───────────────────────────────────────────────────

    private void mixLoop() {
        try {
            while (running || !localQueue.isEmpty()) {
                short[] local = localQueue.poll(50, TimeUnit.MILLISECONDS);
                if (local == null) continue;
                if (encoder == null) initEncoder();
                if (encoder == null) return; // init failed — bail

                int n = local.length;
                short[] remote = new short[n];        // zero-filled → silence where absent
                RingBuffer ring = remoteRing;
                if (ring != null) ring.read(remote, n);

                short[] mix = new short[n];
                for (int i = 0; i < n; i++) {
                    int m = local[i] + remote[i];
                    if (m > Short.MAX_VALUE) m = Short.MAX_VALUE;
                    else if (m < Short.MIN_VALUE) m = Short.MIN_VALUE;
                    mix[i] = (short) m;
                }

                drainEncoder(false);
                feedEncoder(mix, false);
            }
            // Flush: signal end-of-stream and write the trailer.
            if (encoder != null) {
                feedEncoder(new short[0], true);
                drainEncoder(true);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "mixLoop failed", t);
        } finally {
            releaseEncoder();
        }
    }

    private void initEncoder() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME, masterRate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Throwable t) {
            Log.e(TAG, "initEncoder failed", t);
            releaseEncoder();
            encoder = null;
        }
    }

    private void feedEncoder(short[] mono, boolean endOfStream) {
        if (encoder == null) return;
        byte[] bytes = shortsToLe(mono);
        // Retry briefly so we don't silently drop frames (or, worse, the EOS marker) when the
        // encoder momentarily has no free input buffer.
        for (int attempt = 0; attempt < 50; attempt++) {
            int idx = encoder.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer in = encoder.getInputBuffer(idx);
                if (in != null) {
                    in.clear();
                    in.put(bytes);
                }
                long ptsUs = totalSamplesEncoded * 1_000_000L / masterRate;
                encoder.queueInputBuffer(idx, 0, bytes.length, ptsUs,
                        endOfStream ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                totalSamplesEncoded += mono.length;
                return;
            }
            if (!endOfStream) { drainEncoder(false); }
        }
        if (endOfStream) Log.w(TAG, "Could not queue EOS — encoder never freed an input buffer");
    }

    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) return;
        while (true) {
            int idx = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;   // no output ready yet, come back next frame
                // else keep waiting for the EOS buffer
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted && muxer != null) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (idx >= 0) {
                ByteBuffer out = encoder.getOutputBuffer(idx);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0; // codec config consumed by addTrack, not written
                }
                if (bufferInfo.size > 0 && muxerStarted && out != null) {
                    out.position(bufferInfo.offset);
                    out.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, out, bufferInfo);
                }
                encoder.releaseOutputBuffer(idx, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private void releaseEncoder() {
        try { if (encoder != null) { encoder.stop(); encoder.release(); } }
        catch (Throwable ignored) {}
        encoder = null;
        try {
            if (muxer != null) {
                if (muxerStarted) muxer.stop();
                muxer.release();
            }
        } catch (Throwable ignored) {}
        muxer = null;
    }

    // ── PCM helpers ──────────────────────────────────────────────────────────────

    private static short[] toMonoShorts(byte[] data, int channels) {
        int totalShorts = data.length / 2;
        int frames = totalShorts / channels;
        short[] mono = new short[frames];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int i = (f * channels + c) * 2;
                int s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                sum += s;
            }
            mono[f] = (short) (sum / channels);
        }
        return mono;
    }

    private static short[] toMonoShorts(ByteBuffer audioData, int frames, int channels) {
        ByteBuffer b = audioData.duplicate();
        b.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = b.asShortBuffer();
        short[] mono = new short[frames];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int idx = f * channels + c;
                if (idx < sb.limit()) sum += sb.get(idx);
            }
            mono[f] = (short) (sum / channels);
        }
        return mono;
    }

    /** Linear-interpolation resample of a mono buffer. Returns {@code src} unchanged when rates match. */
    private static short[] resampleMono(short[] src, int srcRate, int dstRate) {
        if (srcRate == dstRate || src.length == 0) return src;
        int outLen = (int) ((long) src.length * dstRate / srcRate);
        if (outLen <= 0) return new short[0];
        short[] out = new short[outLen];
        double step = (double) srcRate / dstRate;
        double pos = 0;
        for (int i = 0; i < outLen; i++) {
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, src.length - 1);
            double frac = pos - i0;
            out[i] = (short) (src[i0] * (1 - frac) + src[i1] * frac);
            pos += step;
        }
        return out;
    }

    private static byte[] shortsToLe(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[i * 2]     = (byte) (s[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((s[i] >> 8) & 0xFF);
        }
        return b;
    }

    // ── Bounded mono ring buffer (overwrites oldest when full) ────────────────────

    private static final class RingBuffer {
        private final short[] buf;
        private final int cap;
        private int head = 0, tail = 0, count = 0;

        RingBuffer(int capacity) {
            this.cap = Math.max(1, capacity);
            this.buf = new short[this.cap];
        }

        synchronized void write(short[] data) {
            for (short s : data) {
                if (count == cap) {           // full → drop oldest to stay bounded
                    head = (head + 1) % cap;
                    count--;
                }
                buf[tail] = s;
                tail = (tail + 1) % cap;
                count++;
            }
        }

        /** Reads up to {@code n} samples into {@code dst}; leaves the remainder untouched (silence). */
        synchronized int read(short[] dst, int n) {
            int r = Math.min(n, count);
            for (int i = 0; i < r; i++) {
                dst[i] = buf[head];
                head = (head + 1) % cap;
                count--;
            }
            return r;
        }
    }
}
