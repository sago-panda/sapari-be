package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.live.domain.exception.LivePermissionDeniedException;
import com.sapari.live.domain.repository.LiveSellerPermissionRepository;

/**
 * 방 생성·방송 시작·송출 키 발급/재발급의 첫 관문 — 관리자가 라이브 권한을 끈 판매자를 403(LIVE-019)으로 막는다.
 * 방 조회·미디어 호출보다 앞에 둔다: 막힌 판매자의 요청이 행 잠금이나 외부 호출을 남기지 않게.
 * 막기 전에 이미 Ready 였던 방은 Ready→Live 승격 지점에서 닫는다({@code GoLiveByRtmpService}).
 *
 * <p>권한 행을 잠그지 않는다 — 관리자 변경과 동시에 들어온 요청 하나가 변경 전 값으로 통과할 수 있다(받아들인 한계).
 */
@Component
@RequiredArgsConstructor
public class SellerLivePermissionGuard {

    private final LiveSellerPermissionRepository liveSellerPermissionRepository;

    public void verifyLiveEnabled(UUID sellerId) {
        if (isBlocked(sellerId)) {
            throw new LivePermissionDeniedException();
        }
    }

    /** 요청이 아닌 경로(OBS 연결 webhook·Ready 고착 배치)용 — 던지지 않고 판정만 돌려준다({@code GoLiveByRtmpService}). */
    public boolean isBlocked(UUID sellerId) {
        return !liveSellerPermissionRepository.isLiveEnabled(sellerId);
    }
}
