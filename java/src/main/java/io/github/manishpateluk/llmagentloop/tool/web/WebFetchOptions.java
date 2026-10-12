package io.github.manishpateluk.llmagentloop.tool.web;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Limits for {@code web_fetch}.
 *
 * @param allowedDomains       if non-empty, only these domains (and their subdomains) may be fetched
 * @param blockedDomains       never fetched, nor their subdomains
 * @param allowPrivateNetworks allow loopback/private/link-local addresses — off by default; turn on
 *                             only for an intranet deployment that needs it (or tests)
 * @param timeout              per request, including each redirect hop
 * @param maxBytes             response bodies beyond this are cut off
 * @param maxRedirects         redirect hops followed, each re-checked against these rules
 * @param defaultReadChars     characters of page text returned per call when the model doesn't say
 *                             (it pages on with {@code offset}); lower it to keep pages small in context
 * @param maxReadChars         most characters of page text the model can ask for in one call
 */
public record WebFetchOptions(
        List<String> allowedDomains,
        List<String> blockedDomains,
        boolean allowPrivateNetworks,
        Duration timeout,
        int maxBytes,
        int maxRedirects,
        int defaultReadChars,
        int maxReadChars) {

    /** The default {@link #defaultReadChars()}. */
    public static final int DEFAULT_READ_CHARS = 20_000;

    /** The default {@link #maxReadChars()}. */
    public static final int MAX_READ_CHARS = 100_000;

    /** Any public host; 20 s timeout; 5 MB; 5 redirects; 20,000 characters per call by default, up to 100,000. */
    public static final WebFetchOptions DEFAULT =
            new WebFetchOptions(List.of(), List.of(), false, Duration.ofSeconds(20), 5 * 1024 * 1024, 5);

    public WebFetchOptions {
        allowedDomains = normalize(allowedDomains);
        blockedDomains = normalize(blockedDomains);
        Objects.requireNonNull(timeout, "timeout");
        if (maxBytes <= 0 || maxRedirects < 0) {
            throw new IllegalArgumentException("maxBytes must be positive and maxRedirects non-negative");
        }
        if (defaultReadChars <= 0 || maxReadChars < defaultReadChars) {
            throw new IllegalArgumentException("defaultReadChars must be positive and at most maxReadChars, was "
                    + defaultReadChars + " and " + maxReadChars);
        }
    }

    /** With the default read sizes ({@link #DEFAULT_READ_CHARS}, {@link #MAX_READ_CHARS}). */
    public WebFetchOptions(List<String> allowedDomains, List<String> blockedDomains, boolean allowPrivateNetworks,
                           Duration timeout, int maxBytes, int maxRedirects) {
        this(allowedDomains, blockedDomains, allowPrivateNetworks, timeout, maxBytes, maxRedirects,
                DEFAULT_READ_CHARS, MAX_READ_CHARS);
    }

    public WebFetchOptions withAllowedDomains(List<String> domains) {
        return new WebFetchOptions(domains, blockedDomains, allowPrivateNetworks, timeout, maxBytes, maxRedirects,
                defaultReadChars, maxReadChars);
    }

    public WebFetchOptions withBlockedDomains(List<String> domains) {
        return new WebFetchOptions(allowedDomains, domains, allowPrivateNetworks, timeout, maxBytes, maxRedirects,
                defaultReadChars, maxReadChars);
    }

    public WebFetchOptions withAllowPrivateNetworks(boolean allow) {
        return new WebFetchOptions(allowedDomains, blockedDomains, allow, timeout, maxBytes, maxRedirects,
                defaultReadChars, maxReadChars);
    }

    /**
     * How much page text {@code web_fetch} returns per call: {@code defaultReadChars} unless the model
     * asks for more, up to {@code maxReadChars}. Longer pages are still read in pieces via {@code offset}.
     */
    public WebFetchOptions withReadChars(int defaultReadChars, int maxReadChars) {
        return new WebFetchOptions(allowedDomains, blockedDomains, allowPrivateNetworks, timeout, maxBytes, maxRedirects,
                defaultReadChars, maxReadChars);
    }

    boolean domainAllowed(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (blockedDomains.stream().anyMatch(d -> matches(h, d))) {
            return false;
        }
        return allowedDomains.isEmpty() || allowedDomains.stream().anyMatch(d -> matches(h, d));
    }

    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static List<String> normalize(List<String> domains) {
        return domains == null ? List.of() : domains.stream().map(d -> d.strip().toLowerCase(Locale.ROOT)).toList();
    }
}
