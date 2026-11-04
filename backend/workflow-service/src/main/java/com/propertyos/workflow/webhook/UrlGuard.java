package com.propertyos.workflow.webhook;

import com.propertyos.common.web.ApiException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Decides whether a company may point a webhook at an address, so the platform cannot be made to call its own internal network (SSRF).
 *
 * <p>The rules, applied when an endpoint is saved <em>and again just before every delivery</em> (a name can be pointed somewhere else after it was
 * approved):</p>
 * <ul>
 *   <li>Only {@code https}; {@code http} is allowed only when {@code requireHttps} is off, which is demo and development, never production.</li>
 *   <li>No credentials in the address, and a real host name or public IP address.</li>
 *   <li>The host is looked up and <b>every</b> address it resolves to must be public: not loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7),
 *       link-local (169.254/16, which includes cloud metadata at 169.254.169.254, and fe80::/10), carrier-grade NAT (100.64/10), "this network" (0/8),
 *       multicast, documentation or benchmark ranges, reserved (240/4), or an IPv6 form that wraps an IPv4 address (mapped, NAT64, 6to4) of such a kind.</li>
 *   <li>Names like {@code localhost} and {@code *.internal}, {@code *.local} and one-word names (which only resolve inside a private network) are refused.</li>
 * </ul>
 * Redirects are never followed by the sender, so a public address cannot bounce a delivery to a private one.
 */
public final class UrlGuard {
    /** Looks a host name up. Replaced in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final List<String> INTERNAL_SUFFIXES = List.of(".localhost", ".local", ".internal", ".localdomain", ".lan", ".home", ".home.arpa", ".corp", ".intranet", ".private");

    private final boolean requireHttps;
    private final boolean allowPrivateNetwork;
    private final Resolver resolver;

    /**
     * @param requireHttps        true in production
     * @param allowPrivateNetwork lets private and loopback addresses through. For automated tests only; it is forced off when {@code requireHttps} is on.
     */
    public UrlGuard(boolean requireHttps, boolean allowPrivateNetwork, Resolver resolver) {
        this.requireHttps = requireHttps;
        this.allowPrivateNetwork = allowPrivateNetwork && !requireHttps;
        this.resolver = resolver;
    }

    public UrlGuard(boolean requireHttps, boolean allowPrivateNetwork) {
        this(requireHttps, allowPrivateNetwork, InetAddress::getAllByName);
    }

    /**
     * Checks an address and returns it parsed.
     *
     * @throws ApiException (400) with a plain explanation when the address is not allowed
     */
    public URI check(String raw) {
        if (raw == null || raw.isBlank()) throw ApiException.badRequest("Enter the address that should receive events.");
        String text = raw.trim();
        if (text.length() > 500) throw ApiException.badRequest("That address is too long (500 characters at most).");
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("That doesn't look like a web address. Start it with https://");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) throw ApiException.badRequest("The address must start with https://");
        if (scheme.equals("http") && requireHttps) throw ApiException.badRequest("Use an https:// address. Plain http is not allowed.");
        if (uri.getUserInfo() != null) throw ApiException.badRequest("Leave passwords and user names out of the address. Use the signature to verify events instead.");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw ApiException.badRequest("That address has no host name.");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw ApiException.badRequest("That port number is not valid.");
        if (!allowPrivateNetwork) {
            String h = host.toLowerCase();
            boolean ipLiteral = h.startsWith("[") || h.matches("[0-9.xXa-fA-F:]+");
            if (!ipLiteral && (!h.contains(".") || h.equals("localhost") || INTERNAL_SUFFIXES.stream().anyMatch(h::endsWith)))
                throw ApiException.badRequest("That host name only works inside a private network. Use a public address.");
            InetAddress[] found;
            try {
                found = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
            } catch (UnknownHostException e) {
                throw ApiException.badRequest("We couldn't find \"" + host + "\". Check the spelling.");
            }
            if (found == null || found.length == 0) throw ApiException.badRequest("We couldn't find \"" + host + "\". Check the spelling.");
            for (InetAddress a : found)
                if (blocked(a)) throw ApiException.badRequest("That address points to a private or internal network, which is not allowed. Use a public address.");
        }
        return uri;
    }

    /** True for any address that is not an ordinary public internet address. */
    public static boolean blocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return blockedV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff, b[3] & 0xff);
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true;                                    // fc00::/7 unique local
            if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return true;   // 2001:db8::/32 documentation
            if (b0 == 0x20 && b1 == 0x02) return blockedV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff, b[5] & 0xff);   // 6to4 wraps an IPv4 address
            if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b)                          // 64:ff9b::/96 NAT64 wraps one too
                return blockedV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff, b[15] & 0xff);
            boolean zeros = true;                                                    // ::a.b.c.d (IPv4-compatible) and ::ffff:a.b.c.d (mapped)
            for (int i = 0; i < 10; i++) if (b[i] != 0) zeros = false;
            if (zeros && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || (b[10] == 0 && b[11] == 0)))
                return blockedV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff, b[15] & 0xff);
        }
        return false;
    }

    private static boolean blockedV4(int a, int b, int c, int d) {
        if (a == 0 || a == 10 || a == 127) return true;                              // this network, private, loopback
        if (a == 100 && b >= 64 && b <= 127) return true;                            // 100.64.0.0/10 carrier-grade NAT
        if (a == 169 && b == 254) return true;                                       // link-local, including the cloud metadata address
        if (a == 172 && b >= 16 && b <= 31) return true;                             // 172.16.0.0/12
        if (a == 192 && b == 168) return true;                                       // 192.168.0.0/16
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return true;                   // 192.0.0.0/24 and 192.0.2.0/24
        if (a == 198 && (b == 18 || b == 19)) return true;                           // 198.18.0.0/15 benchmarking
        if (a == 198 && b == 51 && c == 100) return true;                            // 198.51.100.0/24 documentation
        if (a == 203 && b == 0 && c == 113) return true;                             // 203.0.113.0/24 documentation
        return a >= 224;                                                             // multicast and reserved (224.0.0.0 and up, including 255.255.255.255)
    }
}
