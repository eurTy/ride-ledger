package com.rideledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Verified contacts from actual exchanges. Discovery adverts alone never show a teammate online. */
final class PeerPresence {
    static final long RECENT_MILLIS = 30000;

    static final class Link {
        final String member;
        final PeerRoutePolicy.Channel channel;
        final boolean active;

        Link(String member, PeerRoutePolicy.Channel channel, boolean active) {
            this.member = member;
            this.channel = channel;
            this.active = active;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Link)) return false;
            Link link = (Link) other;
            return member.equals(link.member) && channel == link.channel && active == link.active;
        }

        @Override public int hashCode() { return Objects.hash(member, channel, active); }
    }

    private static final class Contact {
        final Link link;
        final String route;
        final long expires;

        Contact(Link link, String route, long expires) {
            this.link = link;
            this.route = route;
            this.expires = expires;
        }
    }

    private final Map<Object, Contact> sessions = new HashMap<>();
    private final Map<String, Contact> recent = new HashMap<>();

    void authenticated(Object session, String member, PeerRoutePolicy.Channel channel, String route) {
        sessions.put(session, new Contact(new Link(member, channel, true), route, Long.MAX_VALUE));
    }

    void finished(Object session, String verifiedRoute, long now) {
        Contact contact = sessions.remove(session);
        if (contact == null) return;
        String key = contact.link.member + "/" + contact.link.channel;
        if (verifiedRoute == null) {
            Contact previous = recent.get(key);
            if (previous != null && previous.route.equals(contact.route)) recent.remove(key);
        } else {
            Link link = new Link(contact.link.member, contact.link.channel, false);
            recent.put(key, new Contact(link, verifiedRoute, now + RECENT_MILLIS));
        }
    }

    void lost(String route) {
        // A discovery loss does not terminate an already authenticated socket.
        recent.values().removeIf(contact -> contact.route.equals(route));
    }

    void lost(PeerRoutePolicy.Channel channel) {
        sessions.values().removeIf(contact -> contact.link.channel == channel);
        recent.values().removeIf(contact -> contact.link.channel == channel);
    }

    List<Link> links(long now) {
        recent.values().removeIf(contact -> contact.expires <= now);
        Map<String, Contact> best = new TreeMap<>();
        for (Contact contact : recent.values()) choose(best, contact);
        for (Contact contact : sessions.values()) choose(best, contact);
        List<Link> links = new ArrayList<>(best.size());
        for (Contact contact : best.values()) links.add(contact.link);
        return links;
    }

    private static void choose(Map<String, Contact> best, Contact contact) {
        Contact previous = best.get(contact.link.member);
        // Active sockets have an infinite expiry. Otherwise the latest verified
        // exchange is more useful than an older confirmation on a faster channel.
        if (previous == null || contact.expires > previous.expires ||
                (contact.expires == previous.expires && priority(contact.link.channel) < priority(previous.link.channel)))
            best.put(contact.link.member, contact);
    }

    private static int priority(PeerRoutePolicy.Channel channel) {
        if (channel == PeerRoutePolicy.Channel.BLUETOOTH) return 2;
        return channel == PeerRoutePolicy.Channel.HOTSPOT ? 1 : 0;
    }

    long nextExpiry() {
        long expiry = Long.MAX_VALUE;
        for (Contact contact : recent.values()) expiry = Math.min(expiry, contact.expires);
        return expiry;
    }

    void clear() {
        sessions.clear();
        recent.clear();
    }
}
