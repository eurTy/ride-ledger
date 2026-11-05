package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Transport-neutral framing: used unchanged over TCP and secure Bluetooth RFCOMM. */
final class PeerWire {
    static final int MAX_FRAME = 12_000_000;
    interface Progress { void moved(); }
    private final InputStream in;
    private final OutputStream out;
    private final Progress progress;
    long bytesRead, bytesWritten;
    PeerWire(InputStream in, OutputStream out, Progress progress) {
        this.in = in; this.out = out; this.progress = progress;
    }
    JSONObject read() throws Exception {
        byte[] header = exact(4);
        int length = ((header[0] & 255) << 24) | ((header[1] & 255) << 16) |
                ((header[2] & 255) << 8) | (header[3] & 255);
        if (length < 1 || length > MAX_FRAME) throw new IllegalArgumentException("同步帧大小无效");
        return new JSONObject(new String(exact(length), StandardCharsets.UTF_8));
    }
    private byte[] exact(int count) throws Exception {
        byte[] bytes = new byte[count]; int offset = 0;
        while (offset < count) {
            int read = in.read(bytes, offset, Math.min(16384, count - offset));
            if (read < 0) throw new java.io.EOFException("同步连接中断");
            if (read == 0) continue;
            offset += read; bytesRead += read; progress.moved();
        }
        return bytes;
    }
    void write(JSONObject frame) throws Exception {
        byte[] data = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FRAME) throw new IllegalArgumentException("同步帧过大");
        DataOutputStream stream = new DataOutputStream(out);
        stream.writeInt(data.length); bytesWritten += 4; progress.moved();
        for (int offset = 0; offset < data.length; offset += 16384) {
            int count = Math.min(16384, data.length - offset);
            stream.write(data, offset, count); bytesWritten += count; progress.moved();
        }
        stream.flush();
    }
    static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys); StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(','); result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof String) return JSONObject.quote((String) value);
        throw new IllegalArgumentException("不支持的同步数据");
    }
    static String hash(Object value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
        char[] hex = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { result[2 * i] = hex[(bytes[i] & 255) >>> 4]; result[2 * i + 1] = hex[bytes[i] & 15]; }
        return new String(result);
    }
}
