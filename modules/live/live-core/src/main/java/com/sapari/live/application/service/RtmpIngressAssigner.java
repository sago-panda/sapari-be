package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * ingress 배정 UPDATE만 트랜잭션에 담는 얇은 빈.
 *
 * <p>{@code @Modifying} 쿼리는 트랜잭션이 없으면 {@code TransactionRequiredException} 이지만,
 * {@code PrepareIngressService} 에 {@code @Transactional} 을 붙이면 {@code createIngress}(외부 I/O)까지
 * 트랜잭션에 들어간다 — {@code c6b0080} 이 일부러 뗀 것이고 종료 측을 커밋 이후로 옮긴 방향과도 어긋난다.
 * 그래서 UPDATE 한 문장만 별도 빈으로 감싼다. 같은 클래스의 private 메서드로 두면 self-invocation 이라
 * 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않는다.
 *
 * <p>{@code @Transactional} 을 리포지토리가 아니라 여기 두는 건 "트랜잭션 경계는 application 레이어"
 * 규칙(ArchUnit) 때문이다.
 */
@Service
@RequiredArgsConstructor
public class RtmpIngressAssigner {

    private final LiveRoomRepository liveRoomRepository;

    /** @return 획득했으면 true. false 면 경합에서 졌거나 방이 더 이상 Scheduled 가 아니다. */
    @Transactional
    public boolean assignIfAbsent(UUID roomId, UUID sellerId, String ingressId, Instant now) {
        return liveRoomRepository.assignRtmpIngressIfAbsent(roomId, sellerId, ingressId, now);
    }
}
