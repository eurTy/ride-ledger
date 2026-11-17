package com.rideledger;

import com.rideledger.LedgerReceipts.Entry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Runs after ledger confirmation. Bluetooth carries references; only LAN carries image bytes. */
final class ReceiptSync {
    private static final int PAGE = 64, MAX_CONTROL = 32768;
    private static final int IGNORE = 0, HAVE = 1, WANT = 2;
    private final LedgerReceipts receipts;
    private final LedgerStore.Trip key;
    private final PeerWire wire;
    private final String remote;
    private final boolean photos;
    private final PeerProtocol.Listener listener;

    ReceiptSync(LedgerReceipts receipts, LedgerStore.Trip key, PeerWire wire, String remote,
                boolean photos, PeerProtocol.Listener listener) {
        this.receipts = receipts;
        this.key = key;
        this.wire = wire;
        this.remote = remote;
        this.photos = photos;
        this.listener = listener;
    }

    void exchange(boolean initiator) throws Exception {
        if (initiator) {
            send();
            receive();
        } else {
            receive();
            send();
        }
    }

    private void send() throws Exception {
        List<Entry> entries = receipts.list(key.id);
        for (int offset = 0; offset < entries.size(); offset += PAGE) {
            List<Entry> page = entries.subList(offset, Math.min(entries.size(), offset + PAGE));
            JSONArray manifest = new JSONArray();
            for (Entry entry : page) manifest.put(entry.json());
            write("receipt-list", manifest);
            JSONArray states = read("receipt-states");
            if (states.length() != page.size()) throw new IllegalArgumentException("小票应答数目不一致");
            for (int i = 0; i < page.size(); i++) {
                Entry entry = page.get(i);
                switch (states.getInt(i)) {
                    case IGNORE: break;
                    case HAVE: acknowledge(entry); break;
                    case WANT:
                        if (!photos || !entry.available) throw new IllegalArgumentException("当前连接无法传输照片");
                        byte[] bytes = receipts.bytes(key.id, entry, false);
                        wire.writeBytes(SyncCodec.sealReceipt(key, entry.digest, bytes == null ? new byte[0] : bytes));
                        JSONArray confirmation = read("receipt-confirm");
                        if (confirmation.length() != 1) throw new IllegalArgumentException("小票确认无效");
                        if (confirmation.getBoolean(0)) acknowledge(entry);
                        break;
                    default: throw new IllegalArgumentException("小票同步状态无效");
                }
            }
        }
        write("receipt-list", new JSONArray());
    }

    private void receive() throws Exception {
        while (true) {
            JSONArray manifest = read("receipt-list");
            if (manifest.length() == 0) return;
            if (manifest.length() > PAGE) throw new IllegalArgumentException("小票同步批次过大");
            List<Entry> requested = new ArrayList<>();
            JSONArray states = new JSONArray();
            for (int i = 0; i < manifest.length(); i++) {
                Entry entry = Entry.parse(manifest.getJSONObject(i));
                if (receipts.apply(key.id, entry)) listener.receiptsChanged(true);
                Entry current = receipts.get(key.id, entry.eventId);
                if (entry.digest.isEmpty() || !entry.matches(current)) {
                    states.put(IGNORE);
                    continue;
                }
                if (entry.available) acknowledge(entry);
                if (current.available) states.put(HAVE);
                else if (photos && entry.available) {
                    states.put(WANT);
                    requested.add(entry);
                } else states.put(IGNORE);
            }
            write("receipt-states", states);
            for (Entry entry : requested) {
                byte[] bytes = SyncCodec.openReceipt(key, entry.digest, wire.readBytes(ReceiptImage.MAX_BYTES + 28));
                if (bytes.length > 0) {
                    ReceiptImage image = ReceiptImage.fromJpeg(bytes);
                    if (!entry.digest.equals(image.digest)) throw new IllegalArgumentException("小票照片校验失败");
                    if (receipts.savePhoto(key.id, entry, image)) listener.receiptsChanged(true);
                }
                Entry current = receipts.get(key.id, entry.eventId);
                write("receipt-confirm", new JSONArray().put(entry.matches(current) && current.available));
            }
        }
    }

    private void acknowledge(Entry entry) {
        if (receipts.acknowledge(key.id, entry, remote)) listener.receiptsChanged(false);
    }

    private void write(String kind, JSONArray values) throws Exception {
        wire.write(SyncCodec.sealPeer(key, new JSONObject().put("kind", kind).put("values", values)));
    }

    private JSONArray read(String kind) throws Exception {
        JSONObject decoded = SyncCodec.openPeer(key, wire.read(MAX_CONTROL));
        if (!kind.equals(decoded.getString("kind"))) throw new IllegalArgumentException("小票同步消息顺序无效");
        return decoded.getJSONArray("values");
    }
}
