package net.yakel.etchedspeakers.client.audio.remote;

import java.util.concurrent.*;

/** Shared bounded preparation pool. Streams submitted here have not been handed to a Channel. */
public final class StreamPreparation {
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"EtchedSpeakers preroll");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private StreamPreparation() {}
    public static <T> CompletableFuture<T> submit(Callable<T> task, Runnable rejectedCleanup) {
        try { return CompletableFuture.supplyAsync(()->{
            try { return task.call(); } catch(Exception e) { throw new CompletionException(e); }
        },WORKERS); }
        catch(RejectedExecutionException e) { rejectedCleanup.run(); return CompletableFuture.failedFuture(e); }
    }
}
