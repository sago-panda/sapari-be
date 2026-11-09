package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;

/**
 * 사전 정본의 한 줄 쓰기를 트랜잭션으로 감싼다.
 *
 * <p><b>왜 별도 빈인가.</b> 쓰기가 {@code @Modifying} 네이티브 쿼리라 트랜잭션 없이는
 * {@code TransactionRequiredException} 이다. 그리고 {@code @Transactional} 이 프록시로 걸리려면
 * 호출이 다른 빈에서 와야 한다 — 같은 클래스 안의 메서드로 두면 자기 호출이라 프록시를 타지
 * 않고, 붙여 놓고 안 걸리는 상태가 된다({@code ChatKickRecorder} 와 같은 이유).
 *
 * <p><b>읽기·검사·쓰기를 한 잠금 아래 둔다({@link #underWriteLock}).</b> 검사가 두 집합 전체를
 * 보므로 동시 쓰기 둘이 각자 자기 결과에서 통과하고 커밋된 합집합만 어길 수 있었다. 두 모양이
 * 있었다:
 * <ul>
 *   <li>제거 — 남은 욕설이 둘일 때 둘을 동시에 지우면 각자 "하나가 남는다" 로 통과하고 사전이
 *       빈다. 빈 정본은 문을 통과하지 못하므로 그 뒤 모든 추가가 409 이고, 마지막 욕설 거부 때문에
 *       제거도 안 된다 — API 로는 빠져나올 길이 없다.
 *   <li>추가 — 규칙 (1)이 두 kind 를 가로지른다({@code ProfanityDictionary.rejectUselessWhitelist}
 *       가 화이트 항목을 <b>단어 집합</b>에 대고 본다). 같은 문자열을 화이트리스트와 욕설에 동시에
 *       넣으면 둘 다 통과하고 합집합만 어긴다.
 * </ul>
 * 잠금이 읽기 앞이라 뒤쪽은 앞쪽의 커밋을 읽고 자기 검사에서 거부된다. 트랜잭션 안에 외부 호출은
 * 없다 — 검사의 트라이 빌드(1,078 항목 p50 0.54 ms)는 CPU 이고, 발행은 이 경계 밖이다.
 *
 * <p><b>잠금 자원이 하나 늘어난다.</b> {@code ChatKickRecorder} 가 그 대가를 적는다 — 자원이
 * 늘면 순서를 지키거나 사이클이 없음을 보여야 한다. 사이클이 없다: 이 잠금을 잡는 트랜잭션은
 * 사전 쓰기 둘뿐이고, 그 안에서 다른 잠금(밴 판정 어드바이저리 등)을 잡지 않으며, 다른 잠금을
 * 잡는 트랜잭션(강퇴·밴 해제)은 이 잠금을 잡지 않는다.
 *
 * <p><b>발행은 이 경계 밖이다.</b> 안에서 발행하면 롤백된 단어가 함대에 깔리고, 그 상태는 정본에
 * 흔적이 없어 어디서 왔는지 찾을 수 없다.
 */
@RequiredArgsConstructor
public class ChatFilterWordWriteRecorder {

    private final ChatFilterWordRepository repository;
    private final ChatAuditLogRepository auditLogRepository;

    /**
     * 사전 쓰기 잠금을 잡고 {@code body} 를 같은 트랜잭션에서 돌린다. {@code body} 안의
     * {@link #add}·{@link #remove} 는 이 트랜잭션에 합류한다(같은 프록시를 거쳐 부르는 한).
     *
     * <p>{@code body} 가 던지면 롤백된다 — 검사 거부가 쓰기 앞이라 롤백할 쓰기는 없다.
     */
    @Transactional
    public <T> T underWriteLock(Supplier<T> body) {
        repository.lockForWrite();
        return body.get();
    }

    /** @return 실제로 새로 들어갔는가. {@code false} 는 이미 있었다는 뜻이고 오류가 아니다 */
    @Transactional
    public boolean add(FilterWordKind kind, String word, UUID actorId, ChatRole actorRole,
                       Instant now) {
        boolean changed = repository.add(kind, word, actorId);
        auditLogRepository.append(auditEntry(
                ChatAuditAction.CHAT_ADD_FILTER_WORD, kind, word, changed, actorId, actorRole, now));
        return changed;
    }

    /** @return 실제로 지워졌는가. {@code false} 는 없었다는 뜻이고 오류가 아니다 */
    @Transactional
    public boolean remove(FilterWordKind kind, String word, UUID actorId, ChatRole actorRole,
                          Instant now) {
        boolean changed = repository.remove(kind, word);
        auditLogRepository.append(auditEntry(
                ChatAuditAction.CHAT_REMOVE_FILTER_WORD, kind, word, changed, actorId, actorRole, now));
        return changed;
    }

    /**
     * 감사 한 줄. 이 두 조치는 <b>대상을 UUID 로 가리킬 수 없다</b> — 사전의 식별자는 단어
     * 문자열이고, 제거는 정본 행을 지우므로 그 행 UUID 를 남겨도 가리킬 대상이 없다. 그래서
     * {@code target_id} 를 비우고 {@code detail} 이 무엇이었는지를 싣는다
     * ({@link com.sapari.chat.domain.model.ChatAuditAction} 이 그 짝을 들고 있다).
     *
     * <p><b>항목을 평문으로 적는다.</b> 로그 쪽은 길이만 찍는데 그 근거는 민감도가 아니라 한 줄의
     * 분량이다(상한 없이 전량이 걸렸을 때 35,966자 — {@code ProfanityFilterFactory} 의 상수가 그
     * 측정을 적어 둔다). 감사 행은 한 조치가 한 줄이고, 평문이 없으면 제거 조치의 감사가 답해야
     * 하는 유일한 질문에 답을 못 한다.
     *
     * <p>{@code changed} 를 싣는 이유: 거짓이면 정본이 그대로다(추가는 이미 있었고 제거는 없었다).
     * 그 칸이 없으면 "감사에는 제거가 있는데 단어가 그대로" 가 기록 누락처럼 읽힌다.
     */
    private static ChatAuditEntry auditEntry(ChatAuditAction action, FilterWordKind kind,
                                             String word, boolean changed, UUID actorId,
                                             ChatRole actorRole, Instant now) {
        return new ChatAuditEntry(actorId, actorRole, action, null,
                Map.of("kind", kind.name(), "word", word, "changed", Boolean.toString(changed)),
                now);
    }
}
