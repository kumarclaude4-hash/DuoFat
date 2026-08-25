package com.duoshield.app.util;

import android.content.Context;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide bounded executors for short application jobs.
 *
 * <p>The serialized executor is used for message/crypto work whose submission order must be
 * preserved. The I/O executor is for independent network and file operations. Call/WebRTC,
 * long-running transcodes, and activity-owned cancellable work deliberately keep their dedicated
 * executors. Queues are bounded to prevent an event burst from retaining an unbounded activity
 * graph on low-memory devices.
 */
public final class SharedExecutors {
    private static final Object LOCK = new Object();
    private static volatile Executor serial;
    private static volatile Executor io;

    private SharedExecutors() {}

    public static void executeSerial(Context context, Runnable task) {
        serial(context).execute(task);
    }

    public static void executeIo(Context context, Runnable task) {
        io(context).execute(task);
    }

    private static Executor serial(Context context) {
        Executor local = serial;
        if (local != null) return local;
        synchronized (LOCK) {
            if (serial == null) {
                int capacity = DevicePerformanceTier.get(context.getApplicationContext())
                        .shortJobQueueCapacity();
                serial = newPool(1, capacity, "duo-serial");
            }
            return serial;
        }
    }

    private static Executor io(Context context) {
        Executor local = io;
        if (local != null) return local;
        synchronized (LOCK) {
            if (io == null) {
                DevicePerformanceTier tier =
                        DevicePerformanceTier.get(context.getApplicationContext());
                io = newPool(tier.shortIoWorkerCount(), tier.shortJobQueueCapacity(), "duo-io");
            }
            return io;
        }
    }

    private static ThreadPoolExecutor newPool(int workers, int queueCapacity, String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(name),
                (task, pool) -> {
                    // Preserve correctness under a short burst without executing crypto or disk
                    // work on the main thread. Interruption fails closed instead of losing work.
                    if (pool.isShutdown()) throw new RejectedExecutionException("Executor shut down");
                    try {
                        pool.getQueue().put(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("Interrupted while queueing work", e);
                    }
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + '-' + next.getAndIncrement());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
