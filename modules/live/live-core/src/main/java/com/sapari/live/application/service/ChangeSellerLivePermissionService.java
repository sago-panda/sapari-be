package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.ChangeSellerLivePermissionCommand;
import com.sapari.live.domain.repository.LiveAuditLogRepository;
import com.sapari.live.domain.repository.LiveSellerPermissionRepository;
import com.sapari.live.port.ChangeSellerLivePermissionUseCase;

@Service
@RequiredArgsConstructor
public class ChangeSellerLivePermissionService implements ChangeSellerLivePermissionUseCase {

    private final LiveSellerPermissionRepository liveSellerPermissionRepository;
    private final LiveAuditLogRepository liveAuditLogRepository;
    private final TimeProvider timeProvider;

    /** 권한과 감사 기록은 한 트랜잭션 — 기록 없는 변경을 남기지 않는다. 같은 값으로 바꿔도 요청마다 기록한다. */
    @Override
    @Transactional
    public void change(ChangeSellerLivePermissionCommand command) {
        Instant changedAt = timeProvider.now();
        liveSellerPermissionRepository.save(command.sellerId(), command.liveEnabled(), changedAt);
        liveAuditLogRepository.appendSellerLivePermission(
                command.adminId(), command.sellerId(), command.liveEnabled(), command.reason(), changedAt);
    }
}
