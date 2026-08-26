package com.duoshield.app.call;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.AudioTrackSink;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Records both sides of a WebRTC call into a single mixed-down {@code .m4a} (AAC-LC).
 *
 * <h3>Where the audio comes from</h3>
 * <ul>
 *   <li><b>Local (mic)</b> — {@link JavaAudioDeviceModule.SamplesReadyCallback}. This taps the
 *       mic <i>after</i> WebRTC's AEC/NS/AGC, so we capture what the peer actually hears rather
 *       than raw mic noise. This callback only fires if the app owns the audio device module,
 *       which is why {@code CallManager} must build an explicit {@code JavaAudioDeviceModule}.</li>
 *   <li><b>Remote</b> — {@link AudioTrackSink} attached to the remote {@code AudioTrack}.</li>
 * </ul>
 *
 * <h3>Two upstream pitfalls this class is built around</h3>
 * <ol>
 *   <li><b>The delivered buffer must be copied synchronously.</b> WebRTC reuses and invalidates
 *       the {@link ByteBuffer} passed to {@link #onData} as soon as the callback returns. Handing
 *       the buffer itself to another thread produces garbled audio (GetStream stream-webrtc-android
 *       issue #262). Both ingest paths therefore copy into a {@code short[]} inline and return
 *       immediately — no encoding ever happens on a WebRTC thread.</li>
 *   <li><b>{@code absoluteCaptureTimestampMs} is always 0</b> in current builds, so it cannot be
 *       used for presentation timestamps. PTS is instead derived from a cumulative count of
 *       samples actually written to the encoder, which is monotonic by construction.</li>
 * </ol>
 *
 * <h3>Mixing model</h3>
 * Everything is normalised on ingest to a canonical <b>48 kHz / mono / PCM-16</b> and buffered in
 * a per-direction ring. A single encoder thread pulls one 20 ms frame (960 samples) from each ring
 * and sums them with clamping.
 *
 * <p>The critical correctness detail is <b>silence-fill</b>: if a ring is short (recording started
 * before the remote track arrived, peer muted, one-way audio, a stalled stream) the shortfall is
 * filled with zeros rather than skipped. Skipping would let one direction advance faster than the
 * other and the two voices would progressively desynchronise over the call.
 *
 * <p>The encoder thread is paced by wall clock, not by buffer availability, so a dead or bursty
 * stream can neither stall the mix nor race ahead of real time. Rings drop their oldest samples
 * when full, which bounds memory if the encoder ever falls behind.
 *
 * <p>Instances are long-lived: {@code CallManager} creates one per call and registers the taps
 * once. Ingest is a cheap no-op until {@link #start} is called, so the taps can stay attached for
 * the whole call.
 */
public class CallAudioRecorder implements AudioTrackSink, JavaAudioDeviceModule.SamplesReadyCallback {

    private static final String TAG = "CallAudioRecorder";

    // ── Canonical internal format ─────────────────────────────────────────────
    /** WebRTC commonly delivers 48 kHz; matching it makes the usual path resample-free. */
    private static final int SAMPLE_RATE   = 48_000;
    private static final int FRAME_MS      = 20;
    /** 960 samples = 20 ms @ 48 kHz. */
    private static final int FRAME_SAMPLES = SAMPLE_RATE / 1_000 * FRAME_MS;

    private static final String MIME_AAC    = "audio/mp4a-latm";
    private static final int    AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    private static final int    BIT_RATE    = 64_000;

    /** ~4 s of slack per direction. Large enough to absorb GC pauses, small enough to bound RAM. */
    private static final int RING_CAPACITY = SAMPLE_RATE * 4;

    /**
     * Hard ceiling on a single recording.
     *
     * <p>Finished files are encrypted whole-blob in memory by the app's existing media pipeline,
     * so an unbounded recording is an OOM risk. At 64 kbps this cap is roughly 14 MB, which
     * encrypts comfortably. Hitting it stops the recording cleanly and keeps the audio so far.
     */
    private static final long MAX_DURATION_MS = 30 * 60 * 1_000L;

    public interface Listener {
        /** Fired on the main thread once the {@code .m4a} is finalised and playable. */
        void onStopped(String filePath, int durationSeconds);
        /** Fired on the main thread on unrecoverable error. The output file is deleted. */
        void onError(String message);
        /** Fired on the main thread when {@link #MAX_DURATION_MS} forced an automatic stop. */
        void onMaxDurationReached();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final PcmRing localRing  = new PcmRing(RING_CAPACITY);
    private final PcmRing remoteRing = new PcmRing(RING_CAPACITY);

    /**
     * Gates ingest. Volatile because WebRTC's mic and remote-track callbacks run on their own
     * threads and must see {@link #start}/{@link #stop} without locking.
     */
    private volatile boolean recording;
    private volatile boolean stopRequested;

    private Thread     encoderThread;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private int        muxerTrack   = -1;
    private boolean    muxerStarted = false;

    private String   outputPath;
    private Listener listener;

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API — called from the main thread
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isRecording() {
        return recording;
    }

    /**
     * Starts capturing. Returns {@code false} if already recording or if encoder setup failed,
     * in which case no listener callback is fired.
     */
    public boolean start(Context ctx, Listener cb) {
        if (recording) {
            Log.w(TAG, "start() ignored — already recording");
            return false;
        }

        // A previous session's thread may still be finalising its file.
        if (encoderThread != null && encoderThread.isAlive()) {
            try { encoderThread.join(1_000); } catch (InterruptedException ignored) {}
        }

        listener      = cb;
        stopRequested = false;
        muxerTrack    = -1;
        muxerStarted  = false;
        localRing.clear();
        remoteRing.clear();

        try {
            File dir = new File(ctx.getCacheDir(), "call_rec_tmp");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            outputPath = new File(dir, "call_" + System.currentTimeMillis() + ".m4a")
                    .getAbsolutePath();

            MediaFormat fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, /*channels=*/1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * 4);

            codec = MediaCodec.createEncoderByType(MIME_AAC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Open the ingest gate only once the encoder is genuinely ready, so the WebRTC
            // callbacks can never touch a half-configured codec.
            recording = true;

            encoderThread = new Thread(this::runLoop, "CallAudioMix");
            encoderThread.setDaemon(true);
            encoderThread.start();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "start() failed", e);
            recording = false;
            releaseAll();
            deleteOutput();
            listener = null;
            return false;
        }
    }

    /**
     * Requests a stop. Returns immediately; {@link Listener#onStopped} arrives on the main thread
     * once the encoder has drained. Safe to call repeatedly and safe to call when not recording.
     */
    public void stop() {
        if (!recording) return;
        stopRequested = true;
        recording     = false; // close the ingest gate at once
    }

    /**
     * Aborts without a callback and deletes any partial file. Used when the call tears down
     * mid-recording, where there is no UI left to deliver a result to.
     */
    public void cancel() {
        listener      = null;
        stopRequested = true;
        recording     = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ingest — runs on WebRTC threads. Copy and return; never block.
    // ─────────────────────────────────────────────────────────────────────────

    /** Remote audio, via {@link AudioTrackSink} on the remote {@code AudioTrack}. */
    @Override
    public void onData(ByteBuffer audioData,
                       int bitsPerSample,
                       int sampleRate,
                       int numberOfChannels,
                       int numberOfFrames,
                       long absoluteCaptureTimestampMs) { // always 0 — deliberately unused
        if (!recording || audioData == null || bitsPerSample != 16) return;
        try {
            // Copy synchronously: this buffer is invalid the moment we return.
            short[] mono = toMono48k(audioData, numberOfChannels, sampleRate, numberOfFrames);
            if (mono != null) remoteRing.write(mono, mono.length);
        } catch (Exception e) {
            Log.w(TAG, "onData ingest failed", e);
        }
    }

    /** Local mic, post-AEC/NS, via the audio device module. */
    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        if (!recording || samples == null) return;
        try {
            byte[] data = samples.getData();
            if (data == null || data.length < 2) return;
            short[] mono = toMono48k(
                    ByteBuffer.wrap(data),
                    samples.getChannelCount(),
                    samples.getSampleRate(),
                    data.length / 2 / Math.max(1, samples.getChannelCount()));
            if (mono != null) localRing.write(mono, mono.length);
        } catch (Exception e) {
            Log.w(TAG, "mic ingest failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Format normalisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts an interleaved little-endian PCM-16 buffer to mono at {@link #SAMPLE_RATE}.
     * Multi-channel input is averaged (not just left-channel-picked, which would drop audio on
     * hard-panned streams); off-rate input is linearly interpolated.
     */
    private short[] toMono48k(ByteBuffer src, int channels, int sampleRate, int frames) {
        if (frames <= 0) return null;
        int ch = Math.max(1, channels);

        ShortBuffer sb = src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int usable = Math.min(frames, sb.remaining() / ch);
        if (usable <= 0) return null;

        short[] mono = new short[usable];
        if (ch == 1) {
            sb.get(mono, 0, usable);
        } else {
            for (int i = 0; i < usable; i++) {
                int sum = 0;
                for (int c = 0; c < ch; c++) sum += sb.get();
                mono[i] = (short) (sum / ch);
            }
        }

        return sampleRate == SAMPLE_RATE ? mono : resample(mono, sampleRate);
    }

    /** Linear interpolation to {@link #SAMPLE_RATE}. Adequate for speech. */
    private short[] resample(short[] in, int srcRate) {
        if (srcRate <= 0 || in.length == 0) return in;
        int outLen = (int) ((long) in.length * SAMPLE_RATE / srcRate);
        if (outLen <= 0) return null;

        short[] out = new short[outLen];
        double step = (double) in.length / outLen;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int    idx = (int) pos;
            double f   = pos - idx;
            short  a   = in[Math.min(idx, in.length - 1)];
            short  b   = in[Math.min(idx + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * f);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Encoder thread
    // ─────────────────────────────────────────────────────────────────────────

    private void runLoop() {
        short[] localFrame  = new short[FRAME_SAMPLES];
        short[] remoteFrame = new short[FRAME_SAMPLES];
        byte[]  mixedBytes  = new byte[FRAME_SAMPLES * 2];
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();

        long totalSamples  = 0L; // drives PTS
        long framesEmitted = 0L;
        long startMs       = System.currentTimeMillis();
        boolean hitMaxDuration = false;

        try {
            while (!stopRequested) {
                long elapsed = System.currentTimeMillis() - startMs;

                if (elapsed >= MAX_DURATION_MS) {
                    hitMaxDuration = true;
                    break;
                }

                // Wall-clock pacing: emit only frames that real time has caught up to. This keeps
                // the mix at 1x regardless of whether either stream is delivering, so silence-fill
                // can never spin the encoder ahead of real time.
                long framesDue = elapsed / FRAME_MS;
                if (framesEmitted >= framesDue) {
                    Thread.sleep(5);
                    continue;
                }

                while (framesEmitted < framesDue && !stopRequested) {
                    // Short reads are zero-filled, keeping both directions on the same timeline.
                    localRing.read(localFrame, FRAME_SAMPLES);
                    remoteRing.read(remoteFrame, FRAME_SAMPLES);

                    for (int i = 0, b = 0; i < FRAME_SAMPLES; i++) {
                        // Clamp rather than let the sum wrap, which would be audible clicks.
                        int mix = localFrame[i] + remoteFrame[i];
                        if (mix > Short.MAX_VALUE) mix = Short.MAX_VALUE;
                        if (mix < Short.MIN_VALUE) mix = Short.MIN_VALUE;
                        mixedBytes[b++] = (byte) (mix & 0xFF);
                        mixedBytes[b++] = (byte) ((mix >> 8) & 0xFF);
                    }

                    feedEncoder(mixedBytes, mixedBytes.length,
                            totalSamples * 1_000_000L / SAMPLE_RATE);
                    totalSamples += FRAME_SAMPLES;
                    framesEmitted++;

                    drainEncoder(outInfo, 0L);
                }
            }

            // ── Flush: signal EOS, then drain until the encoder confirms it ──────
            int eosIdx = codec.dequeueInputBuffer(100_000L);
            if (eosIdx >= 0) {
                codec.queueInputBuffer(eosIdx, 0, 0,
                        totalSamples * 1_000_000L / SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            boolean eosSeen  = false;
            long    deadline = System.currentTimeMillis() + 3_000L;
            while (!eosSeen && System.currentTimeMillis() < deadline) {
                eosSeen = drainEncoder(outInfo, 10_000L);
            }

            if (muxerStarted) muxer.stop();

        } catch (Exception e) {
            Log.e(TAG, "runLoop error", e);
            recording = false;
            releaseAll();
            deleteOutput();
            dispatchError(e.getMessage() != null ? e.getMessage() : "recording failed");
            return;
        } finally {
            recording = false;
            releaseAll();
        }

        // A muxer that never started produced a zero-track file that no player can open.
        if (!muxerStarted) {
            deleteOutput();
            dispatchError("no audio was captured");
            return;
        }

        final String path        = outputPath;
        final int    durationSec = (int) (totalSamples / SAMPLE_RATE);
        final boolean maxed      = hitMaxDuration;
        mainHandler.post(() -> {
            Listener l = listener;
            listener = null;
            if (l == null) {
                // cancel() raced the flush — nobody is listening, so don't leave the file behind.
                new File(path).delete();
                return;
            }
            if (maxed) l.onMaxDurationReached();
            l.onStopped(path, durationSec);
        });
    }

    private void dispatchError(String msg) {
        mainHandler.post(() -> {
            Listener l = listener;
            listener = null;
            if (l != null) l.onError(msg);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Encoder helpers — same shape as VoiceRecorderHelper's proven drain loop
    // ─────────────────────────────────────────────────────────────────────────

    private void feedEncoder(byte[] data, int length, long ptsUs) {
        int idx = codec.dequeueInputBuffer(0L);
        if (idx < 0) return; // encoder momentarily full; dropping one frame beats blocking
        ByteBuffer in = codec.getInputBuffer(idx);
        if (in == null) return;
        in.clear();
        in.put(data, 0, length);
        codec.queueInputBuffer(idx, 0, length, ptsUs, 0);
    }

    private boolean drainEncoder(MediaCodec.BufferInfo outInfo, long timeoutUs) {
        boolean eosSeen = false;
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(outInfo, timeoutUs);

            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;

            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Fires once; the format now carries the AudioSpecificConfig the muxer needs.
                if (!muxerStarted) {
                    muxerTrack   = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
                continue;
            }

            if (outIdx < 0) break;

            if ((outInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // Already delivered to the muxer via the format-changed path above.
                codec.releaseOutputBuffer(outIdx, false);
                continue;
            }

            if (muxerStarted && outInfo.size > 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null) {
                    outBuf.position(outInfo.offset);
                    outBuf.limit(outInfo.offset + outInfo.size);
                    muxer.writeSampleData(muxerTrack, outBuf, outInfo);
                }
            }
            codec.releaseOutputBuffer(outIdx, false);

            if ((outInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                eosSeen = true;
                break;
            }
        }
        return eosSeen;
    }

    private void releaseAll() {
        if (codec != null) {
            try { codec.stop();    } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (muxer != null) {
            try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
        }
        localRing.clear();
        remoteRing.clear();
    }

    private void deleteOutput() {
        if (outputPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(outputPath).delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PCM ring buffer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fixed-capacity circular PCM buffer, written by WebRTC threads and drained by the encoder
     * thread.
     *
     * <p>On overflow it discards the <i>oldest</i> samples. That bounds memory permanently, and
     * dropping stale audio is preferable to dropping the newest audio (which would clip the live
     * end of the conversation) or to growing without limit.
     */
    private static final class PcmRing {
        private final short[] buf;
        private int head;
        private int size;

        PcmRing(int capacity) {
            buf = new short[capacity];
        }

        synchronized void write(short[] src, int len) {
            int cap = buf.length;
            for (int i = 0; i < len; i++) {
                if (size == cap) {
                    head = (head + 1) % cap; // evict oldest
                    size--;
                }
                buf[(head + size) % cap] = src[i];
                size++;
            }
        }

        /**
         * Reads exactly {@code len} samples, zero-filling any shortfall. The zero-fill is what
         * keeps the two directions time-aligned when one of them is silent or late.
         */
        synchronized void read(short[] dst, int len) {
            int cap   = buf.length;
            int avail = Math.min(len, size);
            for (int i = 0; i < avail; i++) dst[i] = buf[(head + i) % cap];
            for (int i = avail; i < len; i++) dst[i] = 0;
            head = (head + avail) % cap;
            size -= avail;
        }

        synchronized void clear() {
            head = 0;
            size = 0;
        }
    }
}
