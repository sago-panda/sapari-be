package com.sapari.chat.application.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 감사 한 줄에 <b>트랜잭션 경계만</b> 주는 얇은 빈 — {@link ChatKickRecorder} 와 같은 처방이다.
 *
 * <p><b>누가 이것을 쓰는가.</b> DB 트랜잭션이 없는 조치 둘이다 — 강퇴 명단 재적재와 종료 마커
 * 해제. 둘은 Redis 만 쓰므로 감사 쓰기가 조인할 경계가 없고, 경계 없이 {@code @Modifying}
 * 네이티브 INSERT 를 부르면
 * {@code InvalidDataAccessApiUsageException("No active transaction for update or delete query")}
 * 로 실패한다(형제 셋이 같은 이유로 생겼다).
 *
 * <p><b>DB 트랜잭션이 있는 조치 셋은 이것을 쓰지 않는다.</b> 강퇴·밴 해제·사전 쓰기는 자기
 * recorder 안에서 {@code ChatAuditLogRepository} 를 직접 부른다 — 그래야 감사 행이 조치와 같은
 * 트랜잭션에서 커밋되고 둘이 갈리지 않는다. 그 셋을 여기로 보내면 {@code REQUIRES_NEW} 가 조치의
 * 트랜잭션을 <b>중단시키고 별도 커밋</b>을 하므로, 조치가 롤백돼도 감사 행만 남는다 —
 * 일어나지 않은 조치가 기록에 남는 쪽이 비는 쪽보다 나쁜 거짓이다({@link ChatAuditEntry} javadoc).
 *
 * <p>스테레오타입을 달지 않는다. 이 스택을 소유한 앱이 {@code @Bean} 으로 등록한다.
 */
@RequiredArgsConstructor
public class ChatAuditRecorder {

    /**
     * 이 트랜잭션의 상한(초) — {@link ChatKickRecorder#TRANSACTION_TIMEOUT_SECONDS} 를 참조한다.
     *
     * <p>값을 복사하지 않는 이유는 {@link ChatBanReleaseRecorder} 가 적어 둔 것과 같다. 여기서는
     * 쓰는 테이블이 달라 대기를 공유하지 않지만, 상한의 뜻("전체가 무한히 돌지 않게")은 같아서
     * 한쪽만 고쳐 조용히 갈리는 쪽을 막는다.
     */
    static final int TRANSACTION_TIMEOUT_SECONDS = ChatKickRecorder.TRANSACTION_TIMEOUT_SECONDS;

    private final ChatAuditLogRepository auditLogRepository;

    /**
     * 한 줄을 자기 트랜잭션에 적는다. <b>조치가 성공한 뒤에</b> 불러야 한다 — 이 메서드는 그 순서를
     * 강제할 수 없고, 먼저 부르면 실패한 조치가 "수행됨" 으로 남는다.
     *
     * <p>{@code REQUIRES_NEW} 인 것은 호출자가 트랜잭션이 없다는 사실에 기대지 않기 위해서다.
     * {@code REQUIRED} 로 두면 나중에 호출자 위쪽에 경계가 생기는 날 이 쓰기가 <b>조용히</b> 그
     * 경계에 합류해, 그쪽이 롤백되면 감사 행이 함께 사라진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void record(ChatAuditEntry entry) {
        auditLogRepository.append(entry);
    }
}
