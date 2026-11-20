package com.sapari.customer.infrastructure.external;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS가 반환한 주소가 공용 인터넷으로 연결 가능한지 판정한다.
 * 사설·로컬 주소뿐 아니라 CGNAT, 문서, benchmark, transition 등 특수 대역도 보수적으로 차단한다.
 */
public class PublicInternetAddressPolicy {

    /**
     * DNS 결과가 비어 있지 않고 모든 A/AAAA 주소가 공인 주소일 때만 반환한다.
     * 하나라도 위험한 주소가 섞이면 client의 주소 선택 순서와 무관하게 요청 전체를 거부한다.
     */
    public void requireAllPublic(InetAddress[] addresses) throws UnknownHostException {
        if (addresses == null || addresses.length == 0) {
            throw rejectedAddress();
        }

        // 첫 주소가 안전해도 뒤의 A/AAAA로 연결될 수 있으므로 전체가 안전해야 한다.
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw rejectedAddress();
            }
        }
    }

    /** IPv4와 IPv6를 각 주소 체계의 특수 대역 정책으로 나눠 판정한다. */
    private boolean isPublic(InetAddress address) {
        if (address instanceof Inet4Address ipv4Address) {
            return isPublicIpv4(ipv4Address);
        }
        if (address instanceof Inet6Address ipv6Address) {
            return isPublicIpv6(ipv6Address);
        }
        return false;
    }

    /**
     * IPv4의 unspecified, private, loopback, link-local, CGNAT, protocol assignment,
     * documentation, benchmark, multicast, reserved 대역을 거부한다.
     */
    private boolean isPublicIpv4(Inet4Address address) {
        long value = ipv4Value(address.getAddress());
        return !isInIpv4Cidr(value, 0x00000000L, 8)
                && !isInIpv4Cidr(value, 0x0A000000L, 8)
                && !isInIpv4Cidr(value, 0x64400000L, 10)
                && !isInIpv4Cidr(value, 0x7F000000L, 8)
                && !isInIpv4Cidr(value, 0xA9FE0000L, 16)
                && !isInIpv4Cidr(value, 0xAC100000L, 12)
                && !isInIpv4Cidr(value, 0xC0000000L, 24)
                && !isInIpv4Cidr(value, 0xC0000200L, 24)
                && !isInIpv4Cidr(value, 0xC0586300L, 24)
                && !isInIpv4Cidr(value, 0xC0A80000L, 16)
                && !isInIpv4Cidr(value, 0xC6120000L, 15)
                && !isInIpv4Cidr(value, 0xC6336400L, 24)
                && !isInIpv4Cidr(value, 0xCB007100L, 24)
                && !isInIpv4Cidr(value, 0xE0000000L, 4)
                && !isInIpv4Cidr(value, 0xF0000000L, 4);
    }

    /**
     * IPv6는 global unicast(2000::/3)만 허용하되 IETF special assignment, documentation,
     * IPv4를 내장할 수 있는 6to4 대역을 추가로 거부한다.
     */
    private boolean isPublicIpv6(Inet6Address address) {
        byte[] bytes = address.getAddress();
        if ((bytes[0] & 0xE0) != 0x20) {
            // IPv4-mapped/embedded IPv6와 translation 대역은 global unicast 밖이므로 여기서 차단된다.
            return false;
        }
        return !hasIpv6Prefix(bytes, new int[] {0x20, 0x01, 0x00}, 23)
                && !hasIpv6Prefix(bytes, new int[] {0x20, 0x01, 0x0D, 0xB8}, 32)
                && !hasIpv6Prefix(bytes, new int[] {0x20, 0x02}, 16)
                && !hasIpv6Prefix(bytes, new int[] {0x3F, 0xFF, 0x00}, 20);
    }

    /** IPv4 4바이트를 부호 없는 비교가 가능한 long 값으로 변환한다. */
    private long ipv4Value(byte[] bytes) {
        return ((long) bytes[0] & 0xFF) << 24
                | ((long) bytes[1] & 0xFF) << 16
                | ((long) bytes[2] & 0xFF) << 8
                | ((long) bytes[3] & 0xFF);
    }

    /** IPv4 값이 지정한 CIDR에 포함되는지 네트워크 mask로 판정한다. */
    private boolean isInIpv4Cidr(long value, long network, int prefixLength) {
        long mask = (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        return (value & mask) == (network & mask);
    }

    /** IPv6 주소 앞부분이 특수 목적 CIDR prefix와 일치하는지 바이트 단위로 판정한다. */
    private boolean hasIpv6Prefix(byte[] address, int[] prefix, int prefixLength) {
        int completeBytes = prefixLength / 8;
        for (int index = 0; index < completeBytes; index++) {
            if ((address[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return ((address[completeBytes] & 0xFF) & mask) == (prefix[completeBytes] & mask);
    }

    /** 거부된 주소 자체를 메시지에 남기지 않는 DNS 실패를 생성한다. */
    private UnknownHostException rejectedAddress() {
        return new UnknownHostException("social profile image destination is not public");
    }
}
