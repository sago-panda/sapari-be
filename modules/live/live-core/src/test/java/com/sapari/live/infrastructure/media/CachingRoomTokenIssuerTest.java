package com.sapari.live.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.application.port.RoomTokenSignatureSource;
import com.sapari.live.infrastructure.config.RoomTokenCacheProperties;

/**
 * 서명 캐시의 계약을 잰다.
 *
 * <p>가장 중요한 칸은 {@link #theCollidingSlotNeverServesAnotherUsersToken()} 다 — 이 캐시에서
 * 해시 충돌의 결과가 형제 {@code EnterRateLimiter} 와 다르다(거기선 공정성 저하, 여기선 남의
 * 토큰 = 신원 혼동 + PII). 충돌을 <b>우연에 맡기지 않고</b> {@code slots=1} 로 전수 충돌을
 * 만들어 키 비교만이 유일한 방어인 상태에서 겨룬다.
 */
@ExtendWith(MockitoExtension.class)
class CachingRoomTokenIssuerTest {

    private static final long TTL_SECONDS = 30L;

    @Mock
    private RoomTokenIssuer delegate;

    @Mock
    private LiveMetrics metrics;

    private final AtomicLong nanos = new AtomicLong();

    private CachingRoomTokenIssuer issuer(int slots) {
        RoomTokenCacheProperties cache = new RoomTokenCacheProperties(true, slots, TTL_SECONDS);
        return new CachingRoomTokenIssuer(delegate, metrics, cache, nanos::get);
    }

    private static RoomTokenClaims member(UUID userId, UUID roomId) {
        return new RoomTokenClaims(userId, roomId, "BUYER", false, "닉", "a@b.com");
    }

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    @DisplayName("같은 claim 을 TTL 안에 다시 물으면 서명을 다시 치지 않는다")
    void theSameClaimsWithinTtlAreSignedOnce() {
        RoomTokenClaims claims = member(UUID.randomUUID(), UUID.randomUUID());
        when(delegate.issue(claims)).thenReturn("token-1");

        CachingRoomTokenIssuer issuer = issuer(16);
        String first = issuer.issue(claims);
        advanceSeconds(TTL_SECONDS - 1);
        String second = issuer.issue(claims);

        assertThat(first).isEqualTo("token-1");
        assertThat(second).isEqualTo("token-1");
        verify(delegate, times(1)).issue(claims);
        verify(metrics, times(1)).roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        verify(metrics, times(1)).roomTokenIssued(RoomTokenSignatureSource.CACHED);
    }

    @Test
    @DisplayName("TTL 이 지나면 다시 서명한다 — 캐시된 exp 는 처음 서명 시각에 고정돼 있다")
    void theExpiredSlotIsSignedAgain() {
        RoomTokenClaims claims = member(UUID.randomUUID(), UUID.randomUUID());
        when(delegate.issue(claims)).thenReturn("token-1", "token-2");

        CachingRoomTokenIssuer issuer = issuer(16);
        issuer.issue(claims);
        advanceSeconds(TTL_SECONDS);

        assertThat(issuer.issue(claims)).isEqualTo("token-2");
        verify(delegate, times(2)).issue(claims);
        verify(metrics, times(2)).roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        verify(metrics, never()).roomTokenIssued(RoomTokenSignatureSource.CACHED);
    }

    /**
     * 충돌한 슬롯이 <b>남의 토큰을 돌려주지 않는다</b>.
     *
     * <p>{@code slots=1} 이라 서로 다른 claim 이 전부 같은 자리에 떨어진다 — 즉 이 칸에서 적중을
     * 막는 것은 오직 키 비교다. 키 비교를 지우면 B 가 A 의 토큰을 받고, 그 토큰에는 A 의
     * {@code sub}·{@code owner}·{@code email} 이 실려 있다.
     */
    @Test
    @DisplayName("충돌한 슬롯은 남의 토큰을 돌려주지 않는다 — 전수 충돌(slots=1)에서 겨룬다")
    void theCollidingSlotNeverServesAnotherUsersToken() {
        UUID room = UUID.randomUUID();
        RoomTokenClaims a = member(UUID.randomUUID(), room);
        RoomTokenClaims b = member(UUID.randomUUID(), room);
        when(delegate.issue(a)).thenReturn("token-A", "token-A-again");
        when(delegate.issue(b)).thenReturn("token-B");

        CachingRoomTokenIssuer issuer = issuer(1);

        assertThat(issuer.issue(a)).isEqualTo("token-A");
        assertThat(issuer.issue(b)).isEqualTo("token-B");
        // A 의 자리는 B 가 덮었으므로 적중이 아니라 미스여야 한다 — B 의 토큰이 나오면 유출이다.
        assertThat(issuer.issue(a)).isEqualTo("token-A-again");

        verify(metrics, times(3)).roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        verify(metrics, never()).roomTokenIssued(RoomTokenSignatureSource.CACHED);
    }

    @Test
    @DisplayName("게스트는 읽지도 쓰지도 않는다 — 회원 항목을 밀어내면 캐시가 헛돈다")
    void theGuestPathNeitherReadsNorWritesTheCache() {
        RoomTokenClaims memberClaims = member(UUID.randomUUID(), UUID.randomUUID());
        when(delegate.issue(memberClaims)).thenReturn("member-token");

        CachingRoomTokenIssuer issuer = issuer(1);
        issuer.issue(memberClaims);

        // 전수 충돌 상태에서 게스트를 끼운다. 게스트가 쓰면 회원 항목이 밀려 다음 호출이 미스가 된다.
        for (int i = 0; i < 5; i++) {
            RoomTokenClaims guest =
                    new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "GUEST", false, null, null);
            when(delegate.issue(guest)).thenReturn("guest-" + i);
            assertThat(issuer.issue(guest)).isEqualTo("guest-" + i);
        }

        assertThat(issuer.issue(memberClaims)).isEqualTo("member-token");
        verify(delegate, times(1)).issue(memberClaims);
        // 게스트는 분모에도 안 들어간다 — 섞으면 적중률이 눌려 "슬롯이 작다" 와 구분이 안 된다.
        verify(metrics, times(1)).roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        verify(metrics, times(1)).roomTokenIssued(RoomTokenSignatureSource.CACHED);
    }

    @Test
    @DisplayName("claim 여섯 필드 중 하나라도 다르면 다른 토큰이다 — record equals 의 전수성에 기댄다")
    void theEveryClaimFieldParticipatesInTheKey() {
        UUID user = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        RoomTokenClaims base = new RoomTokenClaims(user, room, "BUYER", false, "닉", "a@b.com");
        RoomTokenClaims[] others = {
                new RoomTokenClaims(UUID.randomUUID(), room, "BUYER", false, "닉", "a@b.com"),
                new RoomTokenClaims(user, UUID.randomUUID(), "BUYER", false, "닉", "a@b.com"),
                new RoomTokenClaims(user, room, "SELLER", false, "닉", "a@b.com"),
                new RoomTokenClaims(user, room, "BUYER", true, "닉", "a@b.com"),
                new RoomTokenClaims(user, room, "BUYER", false, "다른닉", "a@b.com"),
                new RoomTokenClaims(user, room, "BUYER", false, "닉", "c@d.com"),
        };
        assertThat(others).hasSize(RoomTokenClaims.class.getRecordComponents().length);

        CachingRoomTokenIssuer issuer = issuer(1);
        when(delegate.issue(base)).thenReturn("base");
        issuer.issue(base);

        for (RoomTokenClaims other : others) {
            when(delegate.issue(other)).thenReturn("other");
            assertThat(issuer.issue(other)).isEqualTo("other");
        }
    }

    @Test
    @DisplayName("회원 경로는 역할이 무엇이든 캐시를 탄다 — GUEST 문자열만 예외다")
    void theOnlyUncachedRoleIsGuest() {
        CachingRoomTokenIssuer issuer = issuer(16);
        for (String role : new String[]{"BUYER", "SELLER", "ADMIN"}) {
            RoomTokenClaims claims =
                    new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), role, false, "닉", "a@b.com");
            when(delegate.issue(claims)).thenReturn(role + "-token");
            assertThat(issuer.issue(claims)).isEqualTo(role + "-token");
            assertThat(issuer.issue(claims)).isEqualTo(role + "-token");
            verify(delegate, times(1)).issue(claims);
        }
        verify(metrics, times(3)).roomTokenIssued(RoomTokenSignatureSource.CACHED);
    }

    @Test
    @DisplayName("delegate 의 예외는 삼키지 않는다 — 발급 실패가 캐시 때문에 가려지면 안 된다")
    void theDelegateFailurePropagates() {
        RoomTokenClaims claims = member(UUID.randomUUID(), UUID.randomUUID());
        when(delegate.issue(any())).thenThrow(new IllegalStateException("서명 실패"));

        CachingRoomTokenIssuer issuer = issuer(16);
        try {
            issuer.issue(claims);
            assertThat(false).as("예외가 전파돼야 한다").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("서명 실패");
        }
        verify(metrics, never()).roomTokenIssued(any());
    }
}
