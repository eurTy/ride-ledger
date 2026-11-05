package com.rideledger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hotspot/LAN discovery and sockets only; ledger synchronization is shared with Bluetooth. */
final class LanSync {
    interface Listener { void changed(String status, int newEvents); }
    interface Events {
        void found(PeerRoutePolicy.Endpoint endpoint);
        void lost(String key);
        void incoming(PeerConnection connection, String address);
        void changed(String status);
        void networkChanged();
    }
    private static final String SERVICE_TYPE = "_rideledger._tcp.";
    private final Context context;
    private final String deviceId;
    private final String teamScope;
    private final Events events;
    private final LedgerStore standaloneStore;
    private final Listener standaloneListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, PeerRoutePolicy.Endpoint> peers = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.RegistrationListener registration;
    private HotspotDiscovery hotspotDiscovery;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ServerSocket server;
    private ExecutorService workers;
    private volatile boolean running;
    private volatile String status = "局域网同步未启动";
    private String ownServiceName = "";
    private int generation, discoveryEpoch;
    private boolean resolving;
    private volatile boolean gatewayIsPeer;

    LanSync(Context context, String deviceId, Events events) {
        this(context, deviceId, "", events);
    }
    LanSync(Context context, String deviceId, String teamScope, Events events) {
        this.context = context.getApplicationContext(); this.deviceId = deviceId; this.events = events;
        this.teamScope = teamScope;
        standaloneStore = null; standaloneListener = null;
    }
    // Compatibility entry point for the native v1 interoperability checks.
    LanSync(Context context, LedgerStore store, Listener listener) {
        this.context = context.getApplicationContext(); deviceId = PeerIdentity.deviceId(context); events = null;
        teamScope = "";
        standaloneStore = store; standaloneListener = listener;
    }
    private String serviceType() { return teamScope.isEmpty() ? SERVICE_TYPE : "_rl" + teamScope + "._tcp."; }
    synchronized void start() {
        if (running) return;
        gatewayIsPeer = false;
        try {
            server = new ServerSocket(0); running = true; generation++;
            if (events == null) workers = Executors.newFixedThreadPool(4);
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("RideLedgerDiscovery");
                multicastLock.setReferenceCounted(false); multicastLock.acquire();
            }
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            final ServerSocket listening = server;
            Thread acceptor = new Thread(() -> acceptLoop(listening), "RideLedger-LAN-listen");
            acceptor.setDaemon(true); acceptor.start();
            hotspotDiscovery = new HotspotDiscovery(context, deviceId, server.getLocalPort(),
                    teamScope.isEmpty() ? HotspotDiscovery.DISCOVERY_PORT : 45872, false, teamScope,
                    (address, peer) -> offer("udp:" + peer, address, peer, true));
            hotspotDiscovery.start();
            register(); discover();
            connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { main.post(LanSync.this::networkChanged); }
                @Override public void onLost(Network network) { main.post(LanSync.this::networkChanged); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { main.post(LanSync.this::networkChanged); }
            };
            if (connectivity != null) try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkCallback);
            } catch (Exception ignored) { }
            main.postDelayed(calibrate, 60000);
            if (events == null) main.postDelayed(legacyTick, 10000);
            publish("同一热点下可自动同步 · 本机地址 " + addressLabel(), 0);
        } catch (Exception error) { stop(); publish("热点连接暂不可用，可使用蓝牙", 0); }
    }
    private void register() {
        if (!running || nsd == null) return;
        final int epoch = generation;
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("RideLedger-" + deviceId.substring(0, 8)); info.setServiceType(serviceType()); info.setPort(server.getLocalPort());
            info.setAttribute("p", "2"); info.setAttribute("id", deviceId);
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo service) {
                    if (running && epoch == generation) ownServiceName = service.getServiceName();
                }
                @Override public void onRegistrationFailed(NsdServiceInfo service, int errorCode) {
                    main.post(() -> { if (running && epoch == generation) {
                        registration = null; main.removeCallbacks(retryRegistration); main.postDelayed(retryRegistration, 10000);
                    } });
                }
                @Override public void onServiceUnregistered(NsdServiceInfo service) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo service, int errorCode) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception ignored) {
            registration = null; main.removeCallbacks(retryRegistration); main.postDelayed(retryRegistration, 10000);
        }
    }
    private final Runnable retryRegistration = () -> { if (running && registration == null) register(); };
    private final Runnable calibrate = new Runnable() {
        @Override public void run() { if (running) { restartDiscovery(); main.postDelayed(this, 60000); } }
    };
    private final Runnable legacyTick = new Runnable() {
        @Override public void run() { if (running) { syncNow(); main.postDelayed(this, 10000); } }
    };
    private final Runnable refresh = () -> { if (running) restartDiscovery(); };
    private void networkChanged() {
        if (!running) return;
        gatewayIsPeer = false;
        if (events != null) events.networkChanged();
        for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
        peers.clear(); main.removeCallbacks(refresh); main.postDelayed(refresh, 700);
        if (hotspotDiscovery != null) hotspotDiscovery.networkChanged();
        publish("同一热点下可自动同步 · 本机地址 " + addressLabel(), 0);
    }
    private void restartDiscovery() { stopDiscovery(); discover(); }
    private void discover() {
        if (!running || nsd == null || discovery != null) return;
        final int epoch = ++discoveryEpoch;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) {
                main.post(() -> { if (running && epoch == discoveryEpoch) { stopDiscovery(); main.removeCallbacks(refresh); main.postDelayed(refresh, 10000); } });
            }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onServiceLost(NsdServiceInfo service) {
                main.post(() -> {
                    if (!running || epoch != discoveryEpoch) return;
                    PeerRoutePolicy.Endpoint endpoint = peers.remove(service.getServiceName());
                    if (endpoint != null && events != null) events.lost(endpoint.key);
                });
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                main.post(() -> {
                    if (!running || epoch != discoveryEpoch || service.getServiceName().equals(ownServiceName) || resolveQueue.size() >= 16) return;
                    resolveQueue.add(service); resolveNext(epoch);
                });
            }
        };
        try { nsd.discoverServices(serviceType(), NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception error) { discovery = null; main.postDelayed(refresh, 10000); }
    }
    private void resolveNext(int epoch) {
        if (!running || epoch != discoveryEpoch || resolving || resolveQueue.isEmpty()) return;
        resolving = true; NsdServiceInfo service = resolveQueue.remove();
        try {
            nsd.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo value, int code) { finished(null); }
                @Override public void onServiceResolved(NsdServiceInfo value) { finished(value); }
                private void finished(NsdServiceInfo value) {
                    main.post(() -> {
                        if (!running || epoch != discoveryEpoch) return;
                        resolving = false;
                        if (value != null && value.getHost() != null && value.getPort() > 0) {
                            String peer = attribute(value, "id"); boolean modern = "2".equals(attribute(value, "p"));
                            if (!deviceId.equals(peer)) offer(value.getServiceName(), new InetSocketAddress(value.getHost(), value.getPort()), peer, modern);
                        }
                        resolveNext(epoch);
                    });
                }
            });
        } catch (Exception error) { resolving = false; main.postDelayed(() -> resolveNext(epoch), 500); }
    }
    private static String attribute(NsdServiceInfo service, String key) {
        byte[] value = service.getAttributes().get(key); return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
    private void stopDiscovery() {
        discoveryEpoch++;
        if (discovery != null && nsd != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        discovery = null; resolveQueue.clear(); resolving = false;
    }
    private void offer(String name, InetSocketAddress address, String peerId, boolean modern) {
        if (!running) return;
        if (!teamScope.isEmpty() && !PeerIdentity.valid(peerId)) return;
        if (!teamScope.isEmpty() && isGateway(address.getAddress())) gatewayIsPeer = true;
        String key = "wifi:" + address.getAddress().getHostAddress() + ":" + address.getPort();
        PeerRoutePolicy.Endpoint endpoint = new PeerRoutePolicy.Endpoint(key, channel(address.getAddress()), peerId, modern, () -> dial(address));
        if (peers.size() < 16 || peers.containsKey(name)) peers.put(name, endpoint);
        if (events != null) events.found(endpoint);
        else if (workers != null) workers.execute(() -> exchange(address));
    }
    PeerRoutePolicy.Channel channel(InetAddress host) {
        if (teamScope.isEmpty()) return PeerRoutePolicy.Channel.WIFI;
        try {
            if (localNetwork(context, host) != null) {
                return gatewayIsPeer ? PeerRoutePolicy.Channel.HOTSPOT : PeerRoutePolicy.Channel.WIFI;
            }
            // The phone serving a system hotspot may have a directly connected AP interface
            // without a corresponding Android Network visible to apps.
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface link = interfaces.nextElement();
                if (!link.isUp() || link.isLoopback()) continue;
                for (InterfaceAddress address : link.getInterfaceAddresses())
                    if (sameIpv4Subnet(host, address)) return PeerRoutePolicy.Channel.HOTSPOT;
            }
        } catch (Exception ignored) { }
        return PeerRoutePolicy.Channel.WIFI;
    }
    private static boolean sameIpv4Subnet(InetAddress host, InterfaceAddress link) {
        int prefix = link.getNetworkPrefixLength();
        if (!(host instanceof Inet4Address) || !(link.getAddress() instanceof Inet4Address) || prefix < 1 || prefix > 32)
            return false;
        int mask = -1 << (32 - prefix);
        int remote = ByteBuffer.wrap(host.getAddress()).getInt();
        int local = ByteBuffer.wrap(link.getAddress().getAddress()).getInt();
        return (remote & mask) == (local & mask);
    }
    private boolean isGateway(InetAddress host) {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo info = wifi == null ? null : wifi.getDhcpInfo();
            if (info == null || info.gateway == 0) return false;
            byte[] ip = host.getAddress();
            return ip.length == 4 && info.gateway == ((ip[0] & 255) | (ip[1] & 255) << 8 |
                    (ip[2] & 255) << 16 | (ip[3] & 255) << 24);
        } catch (Exception ignored) { return false; }
    }
    private PeerConnection dial(InetSocketAddress address) throws Exception {
        Socket socket = new Socket(); sockets.add(socket);
        try {
            Network network = localNetwork(context, address.getAddress());
            if (network != null) network.bindSocket(socket);
            socket.connect(address, 3500); socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
            return connection(socket);
        } catch (Exception error) { sockets.remove(socket); try { socket.close(); } catch (Exception ignored) { } throw error; }
    }
    static Network localNetwork(Context context, InetAddress target) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        // A hotspot may have no Internet while cellular remains the system default. Select its local
        // route explicitly for this socket; never bind the whole app or its optional cloud connection.
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (RouteInfo route : properties.getRoutes())
                if (!route.isDefaultRoute() && route.matches(target)) return network;
        }
        // Tethering hosts do not always expose an Android Network; their directly connected AP
        // interface remains reachable through the normal socket path (or the other phone can dial).
        return null;
    }
    private PeerConnection connection(Socket socket) throws Exception {
        return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, () -> sockets.remove(socket));
    }
    private void acceptLoop(ServerSocket listening) {
        while (running && server == listening) {
            try {
                Socket socket = listening.accept();
                if (!running || sockets.size() >= 8) { socket.close(); continue; }
                sockets.add(socket); socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
                PeerConnection connection = connection(socket);
                if (events != null) events.incoming(connection, socket.getInetAddress().getHostAddress());
                else workers.execute(() -> standalone(connection, false));
            } catch (Exception error) { if (running && server == listening) publish("热点连接待重试", 0); }
        }
    }
    private void standalone(PeerConnection connection, boolean outgoing) {
        int[] added = {0};
        try (PeerConnection peer = connection) {
            PeerProtocol protocol = new PeerProtocol(standaloneStore, deviceId, new PeerProtocol.Listener() {
                @Override public void authenticated(String id) { }
                @Override public void stored(int count) { added[0] += count; }
            });
            PeerProtocol.Result result = outgoing ? protocol.exchange(peer.wire(), false) : protocol.accept(peer.wire());
            if (!result.complete) throw new IllegalStateException("部分记录待重试");
            publish("局域网同步完成，新增 " + added[0] + " 笔", added[0]);
        } catch (Exception error) { publish("局域网接收待重试，已新增 " + added[0] + " 笔", added[0]); }
    }
    private void exchange(InetSocketAddress endpoint) {
        try { standalone(dial(endpoint), true); }
        catch (Exception error) { publish("等待同伴设备，已新增 0 笔", 0); }
    }
    void syncNow() {
        if (!running) return;
        for (PeerRoutePolicy.Endpoint endpoint : peers.values()) {
            if (events != null) events.found(endpoint);
            else if (workers != null) workers.execute(() -> exchange(new InetSocketAddress(endpoint.key.substring(5).split(":")[0],
                    Integer.parseInt(endpoint.key.substring(endpoint.key.lastIndexOf(':') + 1)))));
        }
    }
    void connectManual(String raw) {
        String[] parts = raw.trim().split(":");
        if (parts.length != 2 || !parts[0].matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("请输入有效的 IP:端口");
        for (String piece : parts[0].split("\\.")) if (Integer.parseInt(piece) > 255) throw new IllegalArgumentException("IP 地址无效");
        int port = Integer.parseInt(parts[1]); if (port < 1 || port > 65535) throw new IllegalArgumentException("端口无效");
        offer("manual", new InetSocketAddress(parts[0], port), "", false);
    }
    String addressLabel() {
        if (server == null) return "等待热点";
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) result.add(address.getHostAddress() + ":" + server.getLocalPort());
                }
            }
        } catch (Exception ignored) { }
        return result.isEmpty() ? "等待热点" : String.join(" / ", result);
    }
    String status() { return status; }
    private void publish(String value, int added) {
        status = value;
        main.post(() -> { if (events != null) events.changed(value); else standaloneListener.changed(value, added); });
    }
    synchronized void stop() {
        running = false; generation++;
        main.removeCallbacks(calibrate); main.removeCallbacks(legacyTick); main.removeCallbacks(refresh); main.removeCallbacks(retryRegistration);
        if (hotspotDiscovery != null) hotspotDiscovery.stop(); hotspotDiscovery = null;
        stopDiscovery();
        if (registration != null && nsd != null) try { nsd.unregisterService(registration); } catch (Exception ignored) { }
        registration = null;
        if (networkCallback != null && connectivity != null) try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        networkCallback = null;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
        for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
        sockets.clear(); peers.clear();
        if (workers != null) workers.shutdownNow(); workers = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); multicastLock = null;
    }
}
