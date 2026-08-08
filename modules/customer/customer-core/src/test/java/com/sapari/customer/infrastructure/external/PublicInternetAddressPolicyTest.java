package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("공인 인터넷 주소 정책 테스트")
class PublicInternetAddressPolicyTest {

    private final PublicInternetAddressPolicy policy = new PublicInternetAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"})
    @DisplayName("일반 공인 IPv4와 global unicast IPv6를 허용한다")
    void allowsPublicInternetAddresses(String address) throws Exception {
        assertThatCode(() -> policy.requireAllPublic(addresses(address))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("빈 DNS 결과를 거부한다")
    void rejectsEmptyDnsResult() {
        assertThatThrownBy(() -> policy.requireAllPublic(new InetAddress[0]))
                .isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.168.0.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1"
    })
    @DisplayName("사설·로컬·CGNAT·문서·benchmark·multicast·reserved IPv4를 거부한다")
    void rejectsNonPublicIpv4(String address) {
        assertThatThrownBy(() -> policy.requireAllPublic(addresses(address)))
                .isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::",
            "::1",
            "fe80::1",
            "fc00::1",
            "ff02::1",
            "2001:db8::1",
            "2001:1:ffff::1",
            "3fff::1",
            "::ffff:127.0.0.1",
            "64:ff9b::7f00:1",
            "2001::1",
            "2002:7f00:1::1"
    })
    @DisplayName("로컬·특수 IPv6와 IPv4 embedded transition 주소를 거부한다")
    void rejectsNonPublicIpv6(String address) {
        assertThatThrownBy(() -> policy.requireAllPublic(addresses(address)))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("첫 주소가 공인 IPv4여도 뒤 주소가 사설이면 전체 DNS 결과를 거부한다")
    void rejectsMixedPublicAndPrivateIpv4Results() {
        assertThatThrownBy(() -> policy.requireAllPublic(addresses("8.8.8.8", "10.0.0.1")))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("첫 주소가 공인 IPv6여도 뒤 주소가 link-local이면 전체 DNS 결과를 거부한다")
    void rejectsMixedPublicAndLinkLocalIpv6Results() {
        assertThatThrownBy(() -> policy.requireAllPublic(addresses("2606:4700:4700::1111", "fe80::1")))
                .isInstanceOf(UnknownHostException.class);
    }

    private InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] addresses = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) {
            addresses[index] = InetAddress.getByName(values[index]);
        }
        return addresses;
    }
}
