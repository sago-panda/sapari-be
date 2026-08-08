package com.sapari.customer.infrastructure.external;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * 소셜 프로필 이미지 연결에 사용할 DNS 결과 전체를 공인 주소 정책으로 검사한다.
 * 검증한 결과를 Apache 연결 계층에 직접 돌려줘 별도 사전 조회에서 생기는 DNS 재조회 간극을 줄인다.
 */
public class SocialProfileImageDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final PublicInternetAddressPolicy addressPolicy;

    /** 시스템 DNS 조회 결과를 검사하는 production resolver를 생성한다. */
    public SocialProfileImageDnsResolver(PublicInternetAddressPolicy addressPolicy) {
        this(SystemDefaultDnsResolver.INSTANCE, addressPolicy);
    }

    /** 테스트에서 DNS 결과와 실패를 통제할 수 있도록 delegate를 주입한다. */
    SocialProfileImageDnsResolver(DnsResolver delegate, PublicInternetAddressPolicy addressPolicy) {
        this.delegate = delegate;
        this.addressPolicy = addressPolicy;
    }

    /**
     * delegate의 A/AAAA 전체를 검사하고 Apache가 실제 접속에 사용할 동일 배열을 반환한다.
     * 별도 사전 DNS 검사 후 재조회하는 방식보다 검사 주소와 연결 주소가 달라질 가능성을 줄인다.
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        addressPolicy.requireAllPublic(addresses);
        // 이 배열을 Apache 연결 계층이 그대로 사용해야 검증 뒤 다른 주소를 재조회하지 않는다.
        return addresses;
    }

    /** Apache의 canonical hostname 계약은 주소 안전성 판정과 무관하므로 시스템 delegate에 위임한다. */
    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }
}
