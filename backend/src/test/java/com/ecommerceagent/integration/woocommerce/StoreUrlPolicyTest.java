package com.ecommerceagent.integration.woocommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import com.ecommerceagent.integration.woocommerce.StoreUrlPolicy.InvalidStoreUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runtime safety (ADR-0007): the backend never calls private networks or non-HTTPS stores outside local. */
class StoreUrlPolicyTest {

    final StoreUrlPolicy production = new StoreUrlPolicy(false, false, StoreUrlPolicyTest::resolve);

    static InetAddress[] resolve(String host) throws UnknownHostException {
        return switch (host) {
            case "shop.example.com" -> new InetAddress[] {InetAddress.getByName("93.184.215.14")};
            case "rebind.example.com" -> new InetAddress[] {
                    InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")};
            case "metadata.example.com" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            default -> throw new UnknownHostException(host);
        };
    }

    @Test
    void normalizesStoreAddress() {
        assertThat(production.normalize("  HTTPS://Shop.Example.com:443/  ")).isEqualTo(URI.create("https://shop.example.com"));
        assertThat(production.normalize("https://shop.example.com:8443/sklep/")).isEqualTo(URI.create("https://shop.example.com:8443/sklep"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "shop.example.com", "ftp://shop.example.com", "https://user:pw@shop.example.com",
            "https://shop.example.com/?a=1", "https://shop.example.com/#x", "https://shop.example.com/a/../b",
            "https:///path", "https://shop.example.com/%2e%2e"})
    void rejectsInvalidAddresses(String raw) {
        assertThatThrownBy(() -> production.normalize(raw)).isInstanceOf(InvalidStoreUrlException.class);
    }

    @Test
    void rejectsPlainHttpOutsideLocal() {
        assertThatThrownBy(() -> production.normalize("http://shop.example.com"))
                .isInstanceOfSatisfying(InvalidStoreUrlException.class, e -> assertThat(e.code()).isEqualTo("INSECURE_SCHEME"));
        assertThat(new StoreUrlPolicy(true, false, StoreUrlPolicyTest::resolve).normalize("http://localhost:8081"))
                .isEqualTo(URI.create("http://localhost:8081"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.0.8", "198.18.0.1", "::1", "::", "fe80::1", "fd00::1",
            "ff02::1", "::ffff:10.0.0.1", "64:ff9b::a00:1"})
    void blocksNonPublicAddresses(String address) throws Exception {
        assertThat(StoreUrlPolicy.isBlocked(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "1.1.1.1", "2606:4700:4700::1111", "64:ff9b::5db8:d70e"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(StoreUrlPolicy.isBlocked(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void checksEveryResolvedAddressBeforeARequest() throws Exception {
        production.requireAllowedAddress(URI.create("https://shop.example.com"));

        assertThatThrownBy(() -> production.requireAllowedAddress(URI.create("https://rebind.example.com")))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.ADDRESS_NOT_ALLOWED));
        assertThatThrownBy(() -> production.requireAllowedAddress(URI.create("https://metadata.example.com")))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.ADDRESS_NOT_ALLOWED));
        assertThatThrownBy(() -> production.requireAllowedAddress(URI.create("https://unknown.example.com")))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.UNREACHABLE));
        assertThatThrownBy(() -> production.requireAllowedAddress(URI.create("http://shop.example.com")))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.ADDRESS_NOT_ALLOWED));
    }
}
