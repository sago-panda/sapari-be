package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LivePermissionDeniedException;
import com.sapari.live.domain.repository.LiveSellerPermissionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 라이브 권한 관문")
class SellerLivePermissionGuardTest {

    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private LiveSellerPermissionRepository liveSellerPermissionRepository;
    @InjectMocks private SellerLivePermissionGuard guard;

    @Test
    @DisplayName("권한이 꺼져 있으면 403 LIVE-019")
    void disabled_throws403() {
        given(liveSellerPermissionRepository.isLiveEnabled(SELLER)).willReturn(false);

        assertThatThrownBy(() -> guard.verifyLiveEnabled(SELLER))
                .isInstanceOfSatisfying(LivePermissionDeniedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(LiveErrorCode.LIVE_PERMISSION_DENIED);
                    assertThat(e.getErrorCode().getStatus()).isEqualTo(403);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("LIVE-019");
                });
    }

    @Test
    @DisplayName("권한이 켜져 있으면 통과")
    void enabled_passes() {
        given(liveSellerPermissionRepository.isLiveEnabled(SELLER)).willReturn(true);

        assertThatCode(() -> guard.verifyLiveEnabled(SELLER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("isBlocked — 던지지 않고 권한이 꺼졌는지만 돌려준다")
    void isBlocked_reflectsPermission() {
        given(liveSellerPermissionRepository.isLiveEnabled(SELLER)).willReturn(false, true);

        assertThat(guard.isBlocked(SELLER)).isTrue();
        assertThat(guard.isBlocked(SELLER)).isFalse();
    }
}
