package com.rideledger;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Small discovery beacons for hotspots whose mDNS service does not expose the tethering interface.
 * Only a random device ID and a TCP port are advertised. Ledger data still needs trip-key authentication.
 */
final class HotspotDiscovery {
    interface Listener { void found(InetSocketAddress address, String deviceId); }
    private static final int DISCOVERY_PORT = 45871, MAX_PACKET = 1024;
    private final Context context;
    private final String deviceId;
    private final int tcpPort, listenPort;
    private final boolean allowLoopback;
    private final Listener listener;
    private volatile DatagramSocket socket;
    private ScheduledExecutorService timer;

    HotspotDiscovery(Context context, String deviceId, int tcpPort, Listener listener) {
        this(context, deviceId, tcpPort, DISCOVERY_PORT, false, listener);
    }
    HotspotDiscovery(Context context, String deviceId, int tcpPort, int listenPort, boolean allowLoopback, Listener listener) {
        this.context = context; this.deviceId = deviceId; this.tcpPort = tcpPort;
        this.listenPort = listenPort; this.allowLoopback = allowLoopback; this.listener = listener;
    }
    synchronized void start() {
        if (socket != null) return;
        DatagramSocket listening = null;
        try {
            listening = new DatagramSocket(null); listening.setReuseAddress(true); listening.setBroadcast(true);
            listening.bind(new InetSocketAddress(listenPort)); socket = listening;
            final DatagramSocket current = listening;
            Thread receiver = new Thread(() -> receive(current), "RideLedger-hotspot-discovery");
            receiver.setDaemon(true); receiver.start();
            timer = Executors.newSingleThreadScheduledExecutor();
            if (!allowLoopback) timer.scheduleWithFixedDelay(this::beacon, 0, 12, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            if (listening != null) listening.close(); socket = null;
        }
    }
    private void receive(DatagramSocket listening) {
        byte[] bytes = new byte[MAX_PACKET];
        long responseWindow = 0; int responses = 0;
        while (socket == listening && !listening.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length); listening.receive(packet);
                InetAddress host = packet.getAddress();
                if (!(host instanceof Inet4Address) || !(host.isSiteLocalAddress() || allowLoopback && host.isLoopbackAddress()) || packet.getLength() >= MAX_PACKET) continue;
                JSONObject value = new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                String peer = value.optString("deviceId"); int port = value.optInt("port");
                if (!"ride-ledger-nearby".equals(value.optString("app")) || value.optInt("version") != 2 ||
                        !PeerIdentity.valid(peer) || peer.equals(deviceId) || port < 1 || port > 65535) continue;
                if (socket != listening) return;
                listener.found(new InetSocketAddress(host, port), peer);
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - responseWindow > 1000) { responseWindow = now; responses = 0; }
                // One reply to a request; replies never produce further replies.
                if (value.optBoolean("request") && responses++ < 16) send(new InetSocketAddress(host, packet.getPort()), false);
            } catch (Exception ignored) { if (listening.isClosed()) return; }
        }
    }
    void networkChanged() {
        ScheduledExecutorService worker = timer;
        if (worker != null && !worker.isShutdown()) try { worker.execute(this::beacon); } catch (Exception ignored) {}
    }
    private void beacon() {
        if (socket == null) return;
        Set<InetAddress> targets = new LinkedHashSet<>();
        try { targets.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements() && targets.size() < 12) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses())
                    if (address.getBroadcast() instanceof Inet4Address) targets.add(address.getBroadcast());
            }
        } catch (Exception ignored) {}
        if (context != null) try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo info = wifi == null ? null : wifi.getDhcpInfo();
            if (info != null && info.gateway != 0) targets.add(InetAddress.getByAddress(new byte[]{
                    (byte) info.gateway, (byte) (info.gateway >> 8), (byte) (info.gateway >> 16), (byte) (info.gateway >> 24)}));
        } catch (Exception ignored) {}
        for (InetAddress target : targets) send(new InetSocketAddress(target, DISCOVERY_PORT), true);
    }
    // Also used by the isolated Android test to exercise actual UDP request/reply without a Wi-Fi radio.
    void probe(InetSocketAddress address) { send(address, true); }
    int port() { DatagramSocket current = socket; return current == null ? -1 : current.getLocalPort(); }
    private void send(InetSocketAddress target, boolean request) {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) return;
        try {
            byte[] bytes = new JSONObject().put("app", "ride-ledger-nearby").put("version", 2)
                    .put("deviceId", deviceId).put("port", tcpPort).put("request", request).toString().getBytes(StandardCharsets.UTF_8);
            current.send(new DatagramPacket(bytes, bytes.length, target));
        } catch (Exception ignored) {}
    }
    synchronized void stop() {
        DatagramSocket current = socket; socket = null;
        if (current != null) current.close();
        if (timer != null) timer.shutdownNow(); timer = null;
    }
}
