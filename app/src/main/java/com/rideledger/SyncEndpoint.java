package com.rideledger;

import java.net.URI;
import java.util.Locale;

final class SyncEndpoint {
    private SyncEndpoint() { }

    static String normalize(String raw, boolean allowLocalHttp) {
        String value = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        if (value.isEmpty()) return "";
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException();
            boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") ||
                    host.startsWith("192.168.") || host.startsWith("10.") ||
                    host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
            if (!scheme.equals("https") && !(allowLocalHttp && local && scheme.equals("http")))
                throw new IllegalArgumentException();
            return value;
        } catch (Exception e) { throw new IllegalArgumentException("同步地址必须是有效的 HTTPS 地址"); }
    }
}
