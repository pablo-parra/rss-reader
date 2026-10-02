package com.pparra.rssreader.fetch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Keeps server-side fetches away from the machine itself and from link-local addresses (cloud metadata endpoints
 * live at 169.254.169.254). Private LAN ranges stay reachable so self-hosted feeds keep working.
 */
final class HostGuard {

    private HostGuard() {
    }

    static void check(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only http and https URLs can be fetched: " + uri);
        }
        if (uri.getHost() == null) {
            throw new IOException("Invalid URL: " + uri);
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (isBlocked(address)) {
                throw new IOException("Refusing to fetch an internal address: " + uri.getHost());
            }
        }
    }

    static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }
}
