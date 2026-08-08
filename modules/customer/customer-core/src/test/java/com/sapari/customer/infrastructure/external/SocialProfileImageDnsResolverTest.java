package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("소셜 프로필 이미지 DNS resolver 테스트")
class SocialProfileImageDnsResolverTest {

    private final PublicInternetAddressPolicy addressPolicy = new PublicInternetAddressPolicy();
    private final DnsResolver delegate = mock(DnsResolver.class);
    private final SocialProfileImageDnsResolver resolver =
            new SocialProfileImageDnsResolver(delegate, addressPolicy);

    @Test
    @DisplayName("공인 주소만 반환되면 검증한 동일 주소 배열을 연결 계층에 반환한다")
    void returnsSameValidatedAddresses() throws Exception {
        InetAddress[] addresses = addresses("8.8.8.8", "2606:4700:4700::1111");
        when(delegate.resolve("image.example")).thenReturn(addresses);

        assertThat(resolver.resolve("image.example")).isSameAs(addresses);
    }

    @Test
    @DisplayName("공인 주소와 사설 주소가 섞이면 DNS 결과를 거부한다")
    void rejectsMixedPublicAndPrivateAddresses() throws Exception {
        when(delegate.resolve("image.example")).thenReturn(addresses("8.8.8.8", "10.0.0.1"));

        assertThatThrownBy(() -> resolver.resolve("image.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("빈 DNS 결과를 거부한다")
    void rejectsEmptyDnsResult() throws Exception {
        when(delegate.resolve("image.example")).thenReturn(new InetAddress[0]);

        assertThatThrownBy(() -> resolver.resolve("image.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("delegate DNS 실패를 그대로 전파한다")
    void propagatesDelegateDnsFailure() throws Exception {
        UnknownHostException failure = new UnknownHostException("dns failed");
        when(delegate.resolve("image.example")).thenThrow(failure);

        assertThatThrownBy(() -> resolver.resolve("image.example")).isSameAs(failure);
    }

    @Test
    @DisplayName("canonical hostname 조회는 delegate 계약에 위임한다")
    void delegatesCanonicalHostnameResolution() throws Exception {
        when(delegate.resolveCanonicalHostname("image.example")).thenReturn("cdn.example");

        assertThat(resolver.resolveCanonicalHostname("image.example")).isEqualTo("cdn.example");
    }

    private InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] addresses = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) {
            addresses[index] = InetAddress.getByName(values[index]);
        }
        return addresses;
    }
}
