package com.propertyos.workflow.webhook;

import com.propertyos.common.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlGuardTest {
    /** Names resolve to the addresses listed here; anything else is unknown. Numeric addresses are parsed, never looked up. */
    private static UrlGuard.Resolver dns(Map<String, String[]> hosts) {
        return host -> {
            String[] ips = hosts.get(host);
            if (ips == null) return InetAddress.getAllByName(host.matches("[0-9a-fA-F:.xX]+") ? host : "unknown.invalid".equals(host) ? "x" : fail(host));
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) out[i] = InetAddress.getByName(ips[i]);
            return out;
        };
    }

    private static String fail(String host) throws UnknownHostException {
        throw new UnknownHostException(host);
    }

    private static final UrlGuard.Resolver PUBLIC_HOSTS = dns(Map.of(
            "hooks.example.com", new String[]{"93.184.216.34"},
            "two.example.com", new String[]{"93.184.216.34", "10.1.2.3"},
            "evil.example.com", new String[]{"10.0.0.9"},
            "meta.example.com", new String[]{"169.254.169.254"},
            "v6.example.com", new String[]{"2606:2800:220:1:248:1893:25c8:1946"}));

    private static UrlGuard production() {
        return new UrlGuard(true, false, PUBLIC_HOSTS);
    }

    private static UrlGuard demo() {
        return new UrlGuard(false, false, PUBLIC_HOSTS);
    }

    private static void rejects(UrlGuard g, String url) {
        assertThatThrownBy(() -> g.check(url)).as(url).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an https address on a public host is allowed")
    void allowed() {
        assertThat(production().check("https://hooks.example.com/webhooks/propertyos").getHost()).isEqualTo("hooks.example.com");
        assertThat(production().check("https://hooks.example.com:8443/in?x=1").getPort()).isEqualTo(8443);
        assertThat(production().check("https://v6.example.com/").getHost()).isEqualTo("v6.example.com");
        assertThat(production().check("https://93.184.216.34/hook").getHost()).isEqualTo("93.184.216.34");
        assertThat(production().check("https://172.32.0.1/").getHost()).isEqualTo("172.32.0.1");   // just outside 172.16.0.0/12
    }

    @Test
    @DisplayName("plain http is refused in production and allowed in demo and development")
    void httpOnlyOutsideProduction() {
        rejects(production(), "http://hooks.example.com/in");
        assertThat(demo().check("http://hooks.example.com/in").getScheme()).isEqualTo("http");
    }

    @Test
    @DisplayName("other schemes, missing hosts, credentials, bad ports and junk are refused")
    void shape() {
        for (String u : new String[]{null, "", "   ", "ftp://hooks.example.com/", "file:///etc/passwd", "gopher://hooks.example.com/", "javascript:alert(1)", "hooks.example.com/in",
                "https://", "https:///path", "https://user:pw@hooks.example.com/", "https://user@hooks.example.com/", "https://hooks.example.com:0/", "https://hooks.example.com:70000/",
                "https://exa mple.com/", "https://" + "a".repeat(600) + ".example.com/"})
            rejects(demo(), u);
    }

    @Test
    @DisplayName("loopback, private, link-local (cloud metadata), carrier-grade NAT and reserved IPv4 addresses are refused")
    void ipv4() {
        UrlGuard g = new UrlGuard(false, false);
        for (String ip : new String[]{"127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255", "169.254.169.254",
                "169.254.0.1", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "192.0.2.10", "198.51.100.7",
                "203.0.113.9", "198.18.0.1", "192.0.0.8"})
            rejects(g, "https://" + ip + "/hook");
    }

    @Test
    @DisplayName("IPv6 loopback, unique-local, link-local and addresses that wrap a private IPv4 address are refused")
    void ipv6() {
        UrlGuard g = new UrlGuard(false, false);
        for (String ip : new String[]{"[::1]", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[::ffff:127.0.0.1]", "[::ffff:10.0.0.1]", "[::ffff:169.254.169.254]",
                "[64:ff9b::7f00:1]", "[64:ff9b::a9fe:a9fe]", "[2002:7f00:1::]", "[2002:a00:1::]", "[2001:db8::1]", "[ff02::1]"})
            rejects(g, "https://" + ip + "/hook");
    }

    @Test
    @DisplayName("tricks that write a private address as a number are caught after the address is read")
    void encodings() {
        UrlGuard g = new UrlGuard(false, false);
        rejects(g, "http://2130706433/");        // 127.0.0.1 as one number
        rejects(g, "http://0x7f.0.0.1/");        // hexadecimal
        rejects(g, "http://017700000001/");      // octal
        rejects(g, "http://127.1/");             // short form
    }

    @Test
    @DisplayName("names that only work on a private network are refused without being looked up")
    void internalNames() {
        for (String u : new String[]{"https://localhost/", "https://LOCALHOST:8080/", "https://api.localhost/", "https://intranet/", "https://redis/", "https://db.internal/",
                "https://printer.local/", "https://service.corp/", "https://thing.lan/", "https://x.home.arpa/", "https://metadata.google.internal/"})
            rejects(production(), u);
    }

    @Test
    @DisplayName("a public-looking name is refused when it resolves to a private address, even if only one of several records is private")
    void rebinding() {
        rejects(production(), "https://evil.example.com/hook");
        rejects(production(), "https://meta.example.com/latest/meta-data/");
        rejects(production(), "https://two.example.com/hook");
    }

    @Test
    @DisplayName("a name that cannot be found is refused with a helpful message")
    void unknownHost() {
        assertThatThrownBy(() -> production().check("https://nothing-here.example.org/")).isInstanceOf(ApiException.class).hasMessageContaining("couldn't find");
    }

    @Test
    @DisplayName("the private-network switch lets a test reach this machine, but never in production")
    void privateSwitch() {
        assertThat(new UrlGuard(false, true).check("http://127.0.0.1:9999/hook").getPort()).isEqualTo(9999);
        assertThat(new UrlGuard(false, true).check("http://localhost:9999/hook").getHost()).isEqualTo("localhost");
        rejects(new UrlGuard(true, true), "https://127.0.0.1/hook");     // production ignores the switch
        rejects(new UrlGuard(true, true), "http://127.0.0.1:9999/hook");
        rejects(new UrlGuard(false, true), "ftp://127.0.0.1/hook");       // the switch relaxes addresses, not rules about the shape
        rejects(new UrlGuard(false, true), "http://user:pw@127.0.0.1/");
    }

    @Test
    @DisplayName("ordinary public addresses are not blocked")
    void publicAddressesPass() throws Exception {
        for (String ip : new String[]{"8.8.8.8", "93.184.216.34", "1.1.1.1", "172.15.255.255", "172.32.0.0", "100.63.255.255", "100.128.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255"})
            assertThat(UrlGuard.blocked(InetAddress.getByName(ip))).as(ip).isFalse();
    }
}
