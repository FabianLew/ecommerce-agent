package com.ecommerceagent.integration.woocommerce;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Which store addresses the backend may call (ADR-0007, SSRF protection). Addresses are normalized when
 * saved and every resolved IP is checked again right before each request.
 */
public final class StoreUrlPolicy {

    /** DNS lookup, replaceable in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Why an address was rejected when saved; {@code code} is part of the API error. */
    public static final class InvalidStoreUrlException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        InvalidStoreUrlException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final boolean allowInsecureHttp;
    private final boolean allowPrivateNetwork;
    private final Resolver resolver;

    public StoreUrlPolicy(boolean allowInsecureHttp, boolean allowPrivateNetwork, Resolver resolver) {
        this.allowInsecureHttp = allowInsecureHttp;
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolver = resolver;
    }

    /** Returns {@code scheme://host[:port][/path]} without a trailing slash. */
    public URI normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            throw new InvalidStoreUrlException("INVALID_URL", "Enter the store address, e.g. https://shop.example.com");
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new InvalidStoreUrlException("INVALID_URL", "The store address is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new InvalidStoreUrlException("INVALID_URL", "The store address must start with https://");
        }
        if ("http".equals(scheme) && !allowInsecureHttp) {
            throw new InvalidStoreUrlException("INSECURE_SCHEME", "The store address must use https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidStoreUrlException("CREDENTIALS_IN_URL", "The store address must not contain a login");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidStoreUrlException("QUERY_NOT_ALLOWED", "Enter the store address without ? or #");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidStoreUrlException("INVALID_URL", "The store address has no host name");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.contains("..") || path.contains("%")) {
            throw new InvalidStoreUrlException("INVALID_URL", "The store address path is not allowed");
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        String authority = host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        return URI.create(scheme + "://" + authority + path);
    }

    /** Resolves the host and rejects it if any address is in a network the backend must not reach. */
    public void requireAllowedAddress(URI url) throws WooClientException {
        if ("http".equals(url.getScheme()) && !allowInsecureHttp) {
            throw new WooClientException(WooClientException.Kind.ADDRESS_NOT_ALLOWED, "Insecure scheme", null);
        }
        if (allowPrivateNetwork) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(url.getHost());
        } catch (UnknownHostException e) {
            throw new WooClientException(WooClientException.Kind.UNREACHABLE, "Unknown host", e);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new WooClientException(WooClientException.Kind.ADDRESS_NOT_ALLOWED,
                        "Address is in a blocked network", null);
            }
        }
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                       // "this" network
                    || (first == 100 && (second & 0xc0) == 64)      // CGNAT 100.64.0.0/10
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF 192.0.0.0/24
                    || (first == 198 && (second == 18 || second == 19))    // benchmarking
                    || first >= 240;                                // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            boolean uniqueLocal = (b[0] & 0xfe) == 0xfc;            // fc00::/7
            boolean nat64 = b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
            if (nat64) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            return uniqueLocal;
        }
        return true;
    }
}
