package com.rideledger;

import android.content.Context;

import java.net.InetAddress;
import java.util.concurrent.ScheduledExecutorService;

/** Builds nearby transports and normalizes radio callbacks for the coordinator. */
final class PeerRadios {
    interface Events {
        void found(PeerRoutePolicy.Endpoint endpoint);
        void lost(String key);
        void lost(PeerRoutePolicy.Channel channel);
        void incoming(PeerConnection connection, PeerRoutePolicy.Channel channel, String address);
        void changed();
    }

    private final LanSync lan;
    private final BluetoothSync legacyBluetooth;
    private final TeamBle teamBluetooth;

    PeerRadios(Context context, LedgerStore.Trip team, String deviceId, String discoveryId, String scope, Events events) {
        lan = new LanSync(context, discoveryId, scope, new LanSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { events.found(endpoint); }
            @Override public void lost(String key) { events.lost(key); }
            @Override public void incoming(PeerConnection connection, String address) {
                events.incoming(connection, lanChannel(team, address), address);
            }
            @Override public void changed(String status) { events.changed(); }
            @Override public void networkChanged() {
                events.lost(PeerRoutePolicy.Channel.WIFI);
                events.lost(PeerRoutePolicy.Channel.HOTSPOT);
            }
        }, team == null ? null : wire -> TeamProtocol.probe(wire, team));
        legacyBluetooth = team == null ? new BluetoothSync(context, new BluetoothSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { events.found(endpoint); }
            @Override public void incoming(PeerConnection connection, String address) {
                events.incoming(connection, PeerRoutePolicy.Channel.BLUETOOTH, address);
            }
            @Override public void changed() { events.changed(); }
            @Override public void reset() { events.lost(PeerRoutePolicy.Channel.BLUETOOTH); }
        }) : null;
        teamBluetooth = team == null ? null : new TeamBle(context, team, deviceId, new TeamBle.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { events.found(endpoint); }
            @Override public void lost(String key) { events.lost(key); }
            @Override public void incoming(PeerConnection connection) {
                events.incoming(connection, PeerRoutePolicy.Channel.BLUETOOTH, "");
            }
            @Override public void reset() { events.lost(PeerRoutePolicy.Channel.BLUETOOTH); }
        });
    }

    private PeerRoutePolicy.Channel lanChannel(LedgerStore.Trip team, String address) {
        if (team != null) try { return lan.channel(InetAddress.getByName(address)); }
        catch (Exception ignored) { }
        return PeerRoutePolicy.Channel.WIFI;
    }

    void start(ScheduledExecutorService timer, boolean background) {
        lan.start(background);
        if (teamBluetooth != null) teamBluetooth.start(timer, background);
        else legacyBluetooth.start();
    }

    void stop() {
        lan.stop();
        if (teamBluetooth != null) teamBluetooth.stop();
        else legacyBluetooth.stop();
    }

    void announce() { lan.announce(); }
    void rediscover() {
        lan.rediscover();
        if (teamBluetooth != null) teamBluetooth.rediscover();
    }
    void refreshLegacyBluetooth() { legacyBluetooth.refresh(); }
    void refreshTeamBluetooth() { if (teamBluetooth != null) teamBluetooth.refresh(); }
    boolean legacyBluetoothEnabled() { return legacyBluetooth.enabled(); }
    void rememberBluetooth(String address, String peer) {
        if (legacyBluetooth != null) legacyBluetooth.rememberAuthenticated(address, peer);
    }
}
