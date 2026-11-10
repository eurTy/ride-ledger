package com.rideledger;

import com.rideledger.LedgerStore.Event;
import com.rideledger.LedgerStore.JournalPosition;
import com.rideledger.LedgerStore.JournalWindow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded range exchange over an authenticated team session. */
final class JournalSync {
    private static final int PAGE = 128, BATCH_BYTES = 48000;
    private final LedgerStore store;
    private final LedgerStore.Trip trip, key;
    private final String peer;
    private final PeerWire wire;
    private final PeerProtocol.Listener listener;
    private final PeerProtocol.Result result = new PeerProtocol.Result();

    JournalSync(LedgerStore store, LedgerStore.Trip trip, String sessionKey, String peer,
                PeerWire wire, PeerProtocol.Listener listener) {
        this.store = store;
        this.trip = trip;
        this.key = trip.withKey(sessionKey);
        this.peer = peer;
        this.wire = wire;
        this.listener = listener;
    }

    PeerProtocol.Result exchange(boolean initiator) throws Exception {
        JournalWindow local = store.journalWindow(trip);
        write("journal", point(local.tip).put("epoch", local.epoch));
        JSONObject offer = read("journal");
        String remoteEpoch = offer.getString("epoch");
        if (!PeerIdentity.valid(remoteEpoch) || remoteEpoch.equals(local.epoch))
            throw new IllegalArgumentException("同伴日志代次无效");
        JournalWindow remote = new JournalWindow(remoteEpoch, position(offer));
        JournalPosition saved = store.journalCursor(trip.id, peer, remote.epoch);
        write("cursor", point(saved));
        JournalPosition requested = position(read("cursor"));
        JournalPosition start = requested.seq <= local.tip.seq &&
                store.validJournalPosition(trip.id, local.epoch, requested) ? requested : JournalPosition.START;
        write("start", point(start));
        JournalPosition remoteStart = position(read("start"));
        if ((!remoteStart.matches(saved) && !remoteStart.matches(JournalPosition.START)) || remoteStart.seq > remote.tip.seq)
            throw new IllegalArgumentException("同伴未确认同步起点");
        listener.authenticated(peer);
        if (initiator) {
            send(local, start, new JournalWindow(remote.epoch, remoteStart));
            receive(local, remote, saved, remoteStart);
        } else {
            receive(local, remote, saved, remoteStart);
            send(local, start, new JournalWindow(remote.epoch, remoteStart));
        }
        result.remoteId = peer;
        result.modern = result.authenticated = result.complete = result.durable = true;
        result.syncPoint = new LedgerStore.SyncPoint(local.tip.seq, local.tip.id);
        result.bytesRead = wire.bytesRead;
        result.bytesWritten = wire.bytesWritten;
        return result;
    }

    private void send(JournalWindow local, JournalPosition start, JournalWindow knownRemote) throws Exception {
        long after = start.seq;
        while (true) {
            List<Event> rows = store.journalPage(trip.id, local, after, knownRemote, PAGE);
            if (rows.isEmpty()) break;
            JSONArray entries = new JSONArray();
            int bytes = 0;
            for (Event event : rows) {
                JSONObject entry = new JSONObject().put("seq", event.localRow).put("event", LedgerStore.journalEvent(event));
                int length = entry.toString().getBytes(StandardCharsets.UTF_8).length;
                if (entries.length() > 0 && length > BATCH_BYTES - bytes) {
                    sendBatch(entries);
                    entries = new JSONArray();
                    bytes = 0;
                }
                entries.put(entry);
                bytes += length;
                after = event.localRow;
            }
            sendBatch(entries);
        }
        write("end", point(local.tip));
        if (!position(read("confirm")).matches(local.tip))
            throw new IllegalStateException("同伴未确认落库位点");
    }

    private void sendBatch(JSONArray entries) throws Exception {
        write("delta", new JSONObject().put("entries", entries));
        result.sent += entries.length();
    }

    private void receive(JournalWindow local, JournalWindow remote, JournalPosition saved,
                         JournalPosition start) throws Exception {
        long after = start.seq;
        JournalPosition expected = saved;
        while (true) {
            JSONObject message = read("");
            if ("end".equals(message.getString("kind"))) {
                if (!remote.tip.matches(position(message))) throw new IllegalArgumentException("同步结束位点不一致");
                store.commitJournal(trip, local.epoch, peer, remote.epoch, expected, remote.tip,
                        new JSONArray(), new ArrayList<>());
                write("confirm", point(remote.tip));
                return;
            }
            if (!"delta".equals(message.getString("kind"))) throw new IllegalArgumentException("流水消息顺序无效");
            JSONArray entries = message.getJSONArray("entries");
            if (entries.length() < 1 || entries.length() > PAGE) throw new IllegalArgumentException("流水批次数量无效");
            JSONArray events = new JSONArray();
            List<Long> sequences = new ArrayList<>(entries.length());
            JSONObject last = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                long seq = sequence(entry);
                if (seq <= after || seq > remote.tip.seq) throw new IllegalArgumentException("流水位点无效");
                after = seq;
                last = entry.getJSONObject("event");
                events.put(last);
                sequences.add(seq);
            }
            JournalPosition next = new JournalPosition(after, last.getString("id"), PeerWire.hash(last));
            if (after == remote.tip.seq && !next.matches(remote.tip))
                throw new IllegalArgumentException("流水末尾与日志摘要不一致");
            int added = store.commitJournal(trip, local.epoch, peer, remote.epoch, expected, next, events, sequences);
            expected = next;
            result.added += added;
            if (added > 0) listener.stored(added);
        }
    }

    private void write(String kind, JSONObject message) throws Exception {
        wire.write(new JSONObject().put("kind", kind).put("bundle",
                SyncCodec.sealPeer(key, message.put("kind", kind))));
    }

    private JSONObject read(String expected) throws Exception {
        JSONObject frame = wire.read();
        String kind = frame.getString("kind");
        JSONObject message = SyncCodec.openPeer(key, frame.getJSONObject("bundle"));
        if ((!expected.isEmpty() && !expected.equals(kind)) || !kind.equals(message.getString("kind")))
            throw new IllegalArgumentException("同步消息顺序无效");
        return message;
    }

    private static JSONObject point(JournalPosition point) throws Exception {
        return new JSONObject().put("seq", point.seq).put("id", point.id).put("digest", point.digest);
    }

    private static long sequence(JSONObject message) throws Exception {
        Object value = message.get("seq");
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0)
            throw new IllegalArgumentException("同步位点序号无效");
        return ((Number) value).longValue();
    }

    private static JournalPosition position(JSONObject message) throws Exception {
        long seq = sequence(message);
        String id = message.getString("id"), digest = message.getString("digest");
        if (seq == 0 ? !id.isEmpty() || !digest.isEmpty() : !PeerIdentity.valid(id) || !digest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("同步位点摘要无效");
        return new JournalPosition(seq, id, digest);
    }
}
