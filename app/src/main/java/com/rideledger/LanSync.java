package com.rideledger;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LanSync {
    interface Listener { void changed(String status, int newEvents); }

    private static final String SERVICE_TYPE = "_rideledger._tcp.";
    private static final int MAX_FRAME = 12_000_000;
    private final Context context;
    private final LedgerStore store;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, InetSocketAddress> peers = new ConcurrentHashMap<>();
    private NsdManager nsd;
    private WifiManager.MulticastLock multicastLock;
    private ServerSocket server;
    private ExecutorService workers;
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.RegistrationListener registration;
    private String ownServiceName = "";
    private boolean running;
    private String status = "局域网同步未启动";

    LanSync(Context context, LedgerStore store, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        try {
            workers = Executors.newCachedThreadPool();
            server = new ServerSocket(0);
            running = true;
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("RideLedgerDiscovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            workers.execute(this::acceptLoop);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("RideLedger-" + Integer.toHexString(server.getLocalPort()));
            info.setServiceType(SERVICE_TYPE);
            info.setPort(server.getLocalPort());
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo service) {
                    ownServiceName = service.getServiceName();
                    publish("局域网监听中：" + addressLabel(), 0);
                }
                @Override public void onRegistrationFailed(NsdServiceInfo service, int errorCode) {
                    publish("服务广播失败，仍可用地址直连", 0);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo service) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo service, int errorCode) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
            discover();
            main.postDelayed(tick, 10000);
            publish("局域网监听中：" + addressLabel(), 0);
        } catch (Exception e) {
            publish("局域网启动失败：" + e.getMessage(), 0);
            stop();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncNow();
            // Restart discovery so joining a hotspot after opening the app is detected.
            stopDiscovery();
            discover();
            main.postDelayed(this, 10000);
        }
    };

    private void discover() {
        if (!running || nsd == null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                publish("自动发现失败，可用地址直连", 0);
            }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
            @Override public void onServiceLost(NsdServiceInfo service) { peers.remove(service.getServiceName()); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!running || service.getServiceName().equals(ownServiceName)) return;
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { }
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            if (!running || info.getHost() == null || info.getPort() <= 0) return;
                            InetSocketAddress endpoint = new InetSocketAddress(info.getHost(), info.getPort());
                            peers.put(info.getServiceName(), endpoint);
                            workers.execute(() -> exchange(endpoint));
                        }
                    });
                } catch (Exception ignored) { }
            }
        };
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception e) { publish("自动发现失败，可用地址直连", 0); }
    }

    synchronized void stop() {
        if (!running && server == null && workers == null) return;
        running = false;
        main.removeCallbacks(tick);
        stopDiscovery();
        if (registration != null) try { nsd.unregisterService(registration); } catch (Exception ignored) { }
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        if (workers != null) workers.shutdownNow();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
        server = null;
        workers = null;
        nsd = null;
        registration = null;
        peers.clear();
        publish("局域网同步已暂停", 0);
    }

    private void stopDiscovery() {
        if (discovery != null && nsd != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
            discovery = null;
        }
    }

    void syncNow() {
        if (!running || workers == null) return;
        for (InetSocketAddress endpoint : peers.values()) workers.execute(() -> exchange(endpoint));
    }

    void connectManual(String address) {
        String[] pieces = address.trim().split(":", 2);
        if (pieces.length != 2) throw new IllegalArgumentException("请输入 IP:端口");
        int port = Integer.parseInt(pieces[1]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口不合法");
        InetSocketAddress endpoint = new InetSocketAddress(pieces[0], port);
        peers.put("manual", endpoint);
        syncNow();
    }

    String addressLabel() {
        int port = server == null ? 0 : server.getLocalPort();
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> ips = iface.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress())
                        addresses.add(ip.getHostAddress() + ":" + port);
                }
            }
        } catch (Exception ignored) { }
        return addresses.isEmpty() ? "等待网络连接" : String.join(" / ", addresses);
    }

    String status() { return status; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> {
                    try (Socket peer = socket) {
                        peer.setSoTimeout(12000);
                        JSONObject incoming = readFrame(peer);
                        writeFrame(peer, localSnapshot());
                        int added = merge(incoming);
                        publish("局域网同步完成，新增 " + added + " 笔", added);
                    } catch (Exception e) { publish("局域网接收失败：" + e.getMessage(), 0); }
                });
            } catch (Exception e) { if (running) publish("局域网监听失败：" + e.getMessage(), 0); }
        }
    }

    private void exchange(InetSocketAddress endpoint) {
        if (!running) return;
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, 3500);
            socket.setSoTimeout(12000);
            writeFrame(socket, localSnapshot());
            int added = merge(readFrame(socket));
            publish("局域网同步完成，新增 " + added + " 笔", added);
        } catch (Exception e) { publish("等待同伴设备：" + e.getMessage(), 0); }
    }

    private JSONObject localSnapshot() throws Exception {
        JSONArray bundles = new JSONArray();
        for (LedgerStore.Trip trip : store.trips()) bundles.put(SyncCodec.bundle(store, trip));
        return new JSONObject().put("version", 1).put("bundles", bundles);
    }

    private int merge(JSONObject message) throws Exception {
        if (message.getInt("version") != 1) throw new IllegalArgumentException("同步协议版本不一致");
        int added = 0;
        JSONArray bundles = message.getJSONArray("bundles");
        for (int i = 0; i < bundles.length(); i++) added += SyncCodec.mergeBundle(store, bundles.getJSONObject(i));
        return added;
    }

    private void writeFrame(Socket socket, JSONObject message) throws Exception {
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IllegalStateException("同步数据超出初稿上限");
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private JSONObject readFrame(Socket socket) throws Exception {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int length = in.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IllegalArgumentException("同步帧长度不合法");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private void publish(String next, int added) {
        status = next;
        main.post(() -> listener.changed(next, added));
    }
}
