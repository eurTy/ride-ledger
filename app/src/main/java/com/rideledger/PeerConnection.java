package com.rideledger;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Closes stalled reads AND writes. BluetoothSocket has no Socket.setSoTimeout equivalent. */
final class PeerConnection implements Closeable {
    final InputStream input;
    final OutputStream output;
    private final Closeable socket;
    private final Runnable onClosed;
    final int listenerPort;
    private final BiFunction<String, Integer, PeerRoutePolicy.Endpoint> callbackRoute;
    private volatile boolean closed;
    private volatile long movedAt;
    private ScheduledFuture<?> deadline;
    private int watchGeneration;
    PeerConnection(InputStream input, OutputStream output, Closeable socket, Runnable onClosed) {
        this(input, output, socket, onClosed, 0, null);
    }
    PeerConnection(InputStream input, OutputStream output, Closeable socket, Runnable onClosed, int listenerPort,
                   BiFunction<String, Integer, PeerRoutePolicy.Endpoint> callbackRoute) {
        this.input = input; this.output = output; this.socket = socket; this.onClosed = onClosed;
        this.listenerPort = listenerPort;
        this.callbackRoute = callbackRoute;
    }
    PeerRoutePolicy.Endpoint callbackEndpoint(String peer, int port) {
        return callbackRoute == null || port < 1 || port > 65535 ? null : callbackRoute.apply(peer, port);
    }
    void watch(ScheduledExecutorService scheduler) {
        watch(scheduler, 15000, 180000);
    }
    synchronized void watch(ScheduledExecutorService scheduler, long idleTimeout, long totalTimeout) {
        if (closed) return;
        if (deadline != null) deadline.cancel(false);
        movedAt = android.os.SystemClock.elapsedRealtime();
        checkDeadline(scheduler, idleTimeout, movedAt + totalTimeout, ++watchGeneration);
    }
    private synchronized void checkDeadline(ScheduledExecutorService scheduler, long idleTimeout, long totalDeadline, int generation) {
        if (closed || watchGeneration != generation) return;
        long delay = Math.min(movedAt + idleTimeout, totalDeadline) - android.os.SystemClock.elapsedRealtime();
        if (delay <= 0) close();
        else if (!scheduler.isShutdown())
            deadline = scheduler.schedule(() -> checkDeadline(scheduler, idleTimeout, totalDeadline, generation), delay, TimeUnit.MILLISECONDS);
    }
    PeerWire wire() { return new PeerWire(input, output, () -> movedAt = android.os.SystemClock.elapsedRealtime()); }
    long lastProgress() { return movedAt; }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (deadline != null) deadline.cancel(false);
        try { socket.close(); } catch (Exception ignored) { }
        if (onClosed != null) onClosed.run();
    }
}
