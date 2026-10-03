package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses URLs a model shouldn't be able to make the server fetch: anything but http(s), and —
 * unless explicitly allowed — any host resolving to a loopback, private, link-local (including
 * cloud metadata endpoints like {@code 169.254.169.254}), carrier-grade NAT, multicast or
 * unspecified address. This is the standard defence against server-side request forgery.
 *
 * <p>Residual risk: the check resolves the host, then the HTTP client resolves it again to
 * connect, so a hostile DNS server could answer differently the second time ("DNS rebinding").
 * Where that matters, also route outbound traffic through an egress proxy or firewall that blocks
 * private ranges.
 */
final class NetworkGuard {

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private NetworkGuard() {
    }

    static void check(URI uri, WebFetchOptions options) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            throw new ToolInputException("Only http and https URLs can be fetched: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ToolInputException("URL has no host: " + uri);
        }
        if (uri.getUserInfo() != null) {
            throw new ToolInputException("URLs with embedded credentials aren't allowed");
        }
        if (!options.domainAllowed(host)) {
            throw new ToolInputException("Fetching from " + host + " isn't allowed");
        }
        if (options.allowPrivateNetworks()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ToolInputException("Unknown host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new ToolInputException("Fetching from private or local network addresses isn't allowed: " + host);
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0                                   // 0.0.0.0/8 "this network"
                    || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                    || (first == 192 && second == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24 IETF protocol assignments
                    || first >= 240;                            // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {                       // fc00::/7 unique local
                return true;
            }
            boolean mappedOrCompatible = true;                  // ::ffff:a.b.c.d and ::a.b.c.d wrap an IPv4 address
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mappedOrCompatible = false;
                    break;
                }
            }
            if (mappedOrCompatible && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF))) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
