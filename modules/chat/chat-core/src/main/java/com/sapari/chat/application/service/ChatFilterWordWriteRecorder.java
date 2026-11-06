package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
 * <p><b>읽기·검사까지 같은 트랜잭션에 넣지 않았다.</b> ⚠️ 한때 그 근거를 "동시 쓰기 둘은 각자
 * 자기 항목이 든 결과 집합을 검사하므로 어기는 항목을 가진 쪽이 자기 검사에서 거부된다" 로
 * 적었다. <b>규칙 (1)에 대해 거짓이다</b> — 그 규칙은 <i>두 kind 를 가로지른다</i>
 * ({@code ProfanityDictionary.rejectUselessWhitelist} 가 화이트 항목을 <b>단어 집합</b>에 대고
 * 본다). 그래서 A 가 화이트리스트에 {@code 시발점} 을, B 가 같은 시각 욕설에 {@code 시발점} 을
 * 넣으면 <b>둘 다 자기 결과에서는 통과</b>하고 커밋된 합집합만 어긴다. 그 뒤 발행은 전 Pod 가
 * 거부하고 이후 모든 추가가 409 로 막힌다(복구는 둘 중 하나 제거).
 *
 * <p><b>락을 두지 않는 쪽을 유지한다 — 다만 비용을 정확히 적는다.</b> 한때 그 비용을 "읽기·검사·
 * 쓰기를 한 트랜잭션에 묶고 그 안에서 Redis 를 치는 것" 으로 적었다. <b>거짓이다</b> — 이 저장소에
 * 그것을 피하는 패턴이 이미 있다: {@code ChatKickRecorder.record} 가 {@code REQUIRES_NEW} 안에서
 * 첫 문장으로 어드바이저리를 잡고, {@code KickUserService} 는 그 호출이 <i>반환된 뒤</i>(커밋 확정)
 * 에야 Redis 로 간다. 사전도 같게 된다 — 어드바이저리 → 읽기 → 검사 → 쓰기 → 커밋 → (경계 밖)
 * 발행. 트랜잭션 안에 외부 호출이 0이고, 그러면 B 는 A 의 커밋 뒤에 읽어 자기 {@code guardSource}
 * 에서 409 를 받는다(동결이 아니라 거부다).
 *
 * <p>실제 비용은 <b>잠금 자원이 하나 늘어난다</b>는 것이다. {@code ChatKickRecorder} 가 그 대가를
 * 명시한다 — "자원이 하나 더 늘면 그 순서를 지키거나 사이클이 없음을 따로 보여야 한다". 사전
 * 잠금은 강퇴·밴 경로와 겹치는 자원이 없어 그 분석이 자명하지만, 자명한 것도 적어야 다음 자원이
 * 들어올 때 기준이 남는다.
 *
 * <p>그 위에서 <b>안 하는 쪽</b>을 고른 근거는 셋이다: 성립 조건이 "같은 문자열을 서로 다른 kind
 * 에 동시에" 이고 관리자 쓰기가 사람 타이핑 빈도라 확률이 낮다 · 결과가 복구 가능하다(어긴 둘 중
 * 하나 제거) · 그리고 <b>귀속이 생겼다</b> — 쓰기 뒤 커밋된 집합을 다시 문에 통과시켜 어기면 그
 * 자리에서 ERROR 로 남긴다({@code ManageFilterWordsService.warnIfCommittedSourceViolates}).
 * 동결 자체를 막지는 않지만 누른 사람의 요청이 만든 상태가 그 요청의 로그에 붙는다.
 *
 * <p>⚠️ 근거를 정확히 적는 것이 이 단락의 요점이다. "Redis 를 트랜잭션에 넣어야 해서" 로 적어
 * 두면 다음 사람이 그 전제로 재검토하고 같은 결론에 또 도달한다 — 실제로는 <b>싸게 닫을 수 있고</b>,
 * 안 닫는 이유가 확률·복구가능성·귀속이다. 그 셋 중 하나가 바뀌면(예: 사전 쓰기가 자동화되어
 * 빈도가 오르면) 결론을 다시 봐야 한다.
 *
 * <p><b>발행은 이 경계 밖이다.</b> 안에서 발행하면 롤백된 단어가 함대에 깔리고, 그 상태는 정본에
 * 흔적이 없어 어디서 왔는지 찾을 수 없다.
 */
@RequiredArgsConstructor
public class ChatFilterWordWriteRecorder {

    private final ChatFilterWordRepository repository;
    private final ChatAuditLogRepository auditLogRepository;

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
