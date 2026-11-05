package com.rideledger;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Closes stalled reads AND writes. BluetoothSocket has no Socket.setSoTimeout equivalent. */
final class PeerConnection implements Closeable {
    final InputStream input;
    final OutputStream output;
    private final Closeable socket;
    private final Runnable onClosed;
    private volatile boolean closed;
    private volatile long movedAt;
    private ScheduledFuture<?> deadline;
    PeerConnection(InputStream input, OutputStream output, Closeable socket, Runnable onClosed) {
        this.input = input; this.output = output; this.socket = socket; this.onClosed = onClosed;
    }
    void watch(ScheduledExecutorService scheduler) {
        watch(scheduler, 15000, 180000);
    }
    synchronized void watch(ScheduledExecutorService scheduler, long idleTimeout, long totalTimeout) {
        if (closed) return;
        movedAt = android.os.SystemClock.elapsedRealtime();
        final long started = movedAt;
        deadline = scheduler.scheduleAtFixedRate(() -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - movedAt > idleTimeout || now - started > totalTimeout) close();
        }, 1, 1, TimeUnit.SECONDS);
    }
    PeerWire wire() { return new PeerWire(input, output, () -> movedAt = android.os.SystemClock.elapsedRealtime()); }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (deadline != null) deadline.cancel(false);
        try { socket.close(); } catch (Exception ignored) { }
        if (onClosed != null) onClosed.run();
    }
}
