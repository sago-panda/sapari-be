package com.sapari.live.application.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.ChangeSellerLivePermissionCommand;
import com.sapari.live.domain.repository.LiveAuditLogRepository;
import com.sapari.live.domain.repository.LiveSellerPermissionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 판매자 라이브 권한 변경 — 권한 저장 + 감사 기록 1건")
class ChangeSellerLivePermissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private LiveSellerPermissionRepository liveSellerPermissionRepository;
    @Mock private LiveAuditLogRepository liveAuditLogRepository;
    @Mock private TimeProvider timeProvider;
    @InjectMocks private ChangeSellerLivePermissionService service;

    @Test
    @DisplayName("끄기 — 권한을 false 로 저장하고, 같은 시각으로 관리자·판매자·값·사유를 감사 기록에 남긴다")
    void disable_savesAndAudits() {
        given(timeProvider.now()).willReturn(NOW);

        service.change(new ChangeSellerLivePermissionCommand(SELLER, ADMIN, false, "정책 위반"));

        InOrder order = inOrder(liveSellerPermissionRepository, liveAuditLogRepository);
        order.verify(liveSellerPermissionRepository).save(SELLER, false, NOW);
        order.verify(liveAuditLogRepository).appendSellerLivePermission(ADMIN, SELLER, false, "정책 위반", NOW);
    }

    @Test
    @DisplayName("다시 켜기 — true 로 저장하고 감사 기록 1건")
    void enable_savesAndAudits() {
        given(timeProvider.now()).willReturn(NOW);

        service.change(new ChangeSellerLivePermissionCommand(SELLER, ADMIN, true, "소명 확인"));

        then(liveSellerPermissionRepository).should().save(SELLER, true, NOW);
        then(liveAuditLogRepository).should().appendSellerLivePermission(ADMIN, SELLER, true, "소명 확인", NOW);
    }
}
