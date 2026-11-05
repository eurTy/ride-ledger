package com.rideledger;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mutual device authentication and first enrollment, followed by the normal durable delta exchange. */
final class TeamProtocol {
    static final class Admission extends Exception {
        Admission(String message) { super(message); }
    }

    private static final String DOMAIN = "ride-ledger-team-session-1";
    private static final int MAX_HANDSHAKE = 8192;
    private final LedgerStore store;
    private final LedgerStore.Trip trip;
    private final Team.Identity local;
    private final PeerProtocol.Listener listener;
    private final Runnable enrolled;

    TeamProtocol(LedgerStore store, LedgerStore.Trip trip, Team.Identity local, PeerProtocol.Listener listener,
                 Runnable enrolled) {
        this.store = store;
        this.trip = trip;
        this.local = local;
        this.listener = listener;
        this.enrolled = enrolled;
    }

    // The discovery hint is scoped to one trip. It is used for routing, never as proof of identity.
    static String routeId(Team team, String device) {
        return fromHint(routeHint(team, device));
    }

    static String routeHint(Team team, String device) {
        return UUID.nameUUIDFromBytes((team.fingerprint + ":" + device).getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 13).replace("-", "");
    }

    static String fromHint(String hint) {
        return UUID.nameUUIDFromBytes(hint.getBytes(StandardCharsets.US_ASCII)).toString();
    }

    PeerProtocol.Result exchange(PeerWire wire, String expectedRoute) throws Exception {
        KeyPair keys = ephemeral();
        JSONObject offer = hello("offer", keys);
        write(wire, trip, offer);
        JSONObject reply = read(wire, trip, "challenge");
        JSONObject challenge = reply.getJSONObject("hello");
        Remote remote = inspect(challenge, "responder", expectedRoute);
        if (remote.grant == null) throw new Admission("等待同伴完成首次登记");
        JSONObject proof = transcript(offer, challenge);
        if (!Team.verify(remote.key, signedAs(proof, "responder"), reply.getString("proof")))
            throw new IllegalArgumentException("同伴设备认证失败");
        write(wire, trip, new JSONObject().put("kind", "proof").put("proof", local.sign(signedAs(proof, "initiator"))));
        LedgerStore.Trip session = trip.withKey(key(keys, remote, proof));
        JSONObject decision = read(wire, session, "admission");
        if (decision.has("reason")) throw new Admission(decision.getString("reason"));
        if (!store.teamReady(trip)) {
            store.acceptSeat(trip, decision.getJSONObject("grant"), local.deviceId, local.key, trip.localPerson);
            enrolled.run();
        }
        store.acceptSeat(trip, remote.grant, remote.device, remote.key, remote.member);
        return new PeerProtocol(store, local.deviceId, listener, trip, remote.device, session.secret).exchange(wire, true);
    }

    PeerProtocol.Result accept(PeerWire wire) throws Exception {
        JSONObject offer = read(wire, trip, "offer");
        Remote remote = inspect(offer, "offer", "");
        if (!store.teamReady(trip)) throw new Admission("正在等待首次登记");
        KeyPair keys = ephemeral();
        JSONObject challenge = hello("responder", keys);
        JSONObject transcript = transcript(offer, challenge);
        write(wire, trip, new JSONObject().put("kind", "challenge").put("hello", challenge)
                .put("proof", local.sign(signedAs(transcript, "responder"))));
        JSONObject proof = read(wire, trip, "proof");
        if (!Team.verify(remote.key, signedAs(transcript, "initiator"), proof.getString("proof")))
            throw new IllegalArgumentException("同伴设备认证失败");
        LedgerStore.Trip session = trip.withKey(key(keys, remote, transcript));
        JSONObject decision = new JSONObject().put("kind", "admission");
        JSONObject grant = remote.grant;
        try {
            if (grant == null) {
                if (!trip.team.issuer.equals(local.deviceId)) throw new Admission("首次登记需与建队设备连通");
                grant = store.enroll(trip, local, remote.device, remote.key, remote.member);
                decision.put("grant", grant);
            }
            store.acceptSeat(trip, grant, remote.device, remote.key, remote.member);
        } catch (Admission denied) {
            write(wire, session, decision.put("reason", denied.getMessage()));
            throw denied;
        }
        write(wire, session, decision);
        return new PeerProtocol(store, local.deviceId, listener, trip, remote.device, session.secret).accept(wire);
    }

    private JSONObject hello(String kind, KeyPair keys) throws Exception {
        return new JSONObject().put("kind", kind).put("team", trip.team.fingerprint)
                .put("nonce", UUID.randomUUID().toString()).put("device", local.deviceId)
                .put("key", local.key).put("member", trip.localPerson)
                .put("ephemeral", Team.encode(keys.getPublic().getEncoded()))
                .put("grant", store.seat(trip.id, local.deviceId));
    }

    private Remote inspect(JSONObject message, String kind, String expectedRoute) throws Exception {
        Remote peer = new Remote(message);
        if (!kind.equals(message.getString("kind")) || !trip.team.fingerprint.equals(message.getString("team")) ||
                !PeerIdentity.valid(message.getString("nonce")) || !PeerIdentity.valid(peer.device) ||
                local.deviceId.equals(peer.device) || trip.indexOf(peer.member) < 0 ||
                (!expectedRoute.isEmpty() && !routeId(trip.team, peer.device).equals(expectedRoute)))
            throw new IllegalArgumentException("小队设备资料无效");
        Team.publicKey(peer.key);
        Team.publicKey(peer.ephemeral);
        if (peer.grant != null) trip.team.verifyGrant(peer.grant, peer.device, peer.key, peer.member);
        return peer;
    }

    private static final class Remote {
        final String device, key, member, ephemeral;
        final JSONObject grant;
        Remote(JSONObject message) throws Exception {
            device = message.getString("device");
            key = message.getString("key");
            member = message.getString("member");
            ephemeral = message.getString("ephemeral");
            grant = message.optJSONObject("grant");
        }
    }

    private static JSONObject transcript(JSONObject offer, JSONObject challenge) throws Exception {
        return new JSONObject().put("domain", DOMAIN).put("offer", PeerWire.hash(offer))
                .put("challenge", PeerWire.hash(challenge));
    }

    private static JSONObject signedAs(JSONObject transcript, String role) throws Exception {
        return new JSONObject(transcript.toString()).put("role", role);
    }

    private static KeyPair ephemeral() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String key(KeyPair keys, Remote peer, JSONObject transcript) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.getPrivate());
        agreement.doPhase(Team.publicKey(peer.ephemeral), true);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Team.decode(trip.secret), "HmacSHA256"));
        byte[] extracted = mac.doFinal(agreement.generateSecret());
        mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
        mac.update(PeerWire.canonical(transcript).getBytes(StandardCharsets.UTF_8));
        return Team.encode(mac.doFinal(new byte[]{1}));
    }

    private static void write(PeerWire wire, LedgerStore.Trip key, JSONObject payload) throws Exception {
        wire.write(SyncCodec.sealPeer(key, payload.put("domain", DOMAIN)));
    }

    private static JSONObject read(PeerWire wire, LedgerStore.Trip key, String kind) throws Exception {
        JSONObject payload = SyncCodec.openPeer(key, wire.read(MAX_HANDSHAKE));
        if (!DOMAIN.equals(payload.getString("domain")) || !kind.equals(payload.getString("kind")))
            throw new IllegalArgumentException("小队协议顺序无效");
        return payload;
    }
}
