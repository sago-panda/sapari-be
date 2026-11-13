package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository.Released;

import lombok.RequiredArgsConstructor;

/**
 * 밴 해제의 <b>DB 쓰기만</b> 한 트랜잭션에 담는 얇은 빈 — {@link ChatKickRecorder}와 같은 처방이다.
 *
 * <p><b>왜 별도 빈인가.</b> 두 쓰기(행 DELETE + 사면 기준선 INSERT)가 모두 {@code @Modifying}
 * 네이티브 쿼리다. Spring Data는 리포지토리 인터페이스에 직접 선언된 그런 쿼리에 기본 트랜잭션을
 * 얹어 주지 않아, 경계 없이 부르면
 * {@code InvalidDataAccessApiUsageException("No active transaction for update or delete query")}로
 * 실패한다. 그리고 자기 호출은 프록시를 타지 않으므로 같은 클래스의 메서드로 둘 수 없다 —
 * 붙여 놓고 안 걸리는 쪽이 안 붙인 것보다 나쁘다.
 *
 * <p><b>왜 미러 삭제를 여기 넣지 않는가.</b> 순서가 정본 → 미러여야 하고, 그 '→' 는 커밋이다.
 * 미러를 트랜잭션 안에서 지우면 커밋 전에 지워지고, 롤백되면 <b>정본에 남은 행 + 사라진 미러</b>가
 * 된다. 그 상태는 스스로 복원된다 — 남은 행이 다음 강퇴의 조기 반환에서 활성 밴으로 잡혀 미러에
 * 다시 써지므로, 운영자는 "풀었는데 다시 막혔다" 를 보게 된다. 그래서 미러는 호출자가 이 메서드가
 * <b>반환된 뒤에</b> 지운다.
 *
 * <p>두 쓰기를 한 트랜잭션에 묶는 것은 반대로 <b>필수</b>다. 갈라지면 "밴은 풀렸는데 누적은 안
 * 접힘"(해제를 다시 눌러 수렴) 또는 "누적은 접혔는데 밴은 남음"(다음 강퇴에서 티어가 내려가 보여
 * 원인 추적이 어렵다)이 된다.
 *
 * <p>스테레오타입을 달지 않는다. 이 스택을 소유한 앱이 {@code @Bean}으로 등록한다.
 */
@RequiredArgsConstructor
public class ChatBanReleaseRecorder {

    /**
     * 이 트랜잭션 전체의 상한(초) — {@link ChatKickRecorder#TRANSACTION_TIMEOUT_SECONDS}와 같은 값이다.
     *
     * <p>같은 테이블에 같은 사용자 키로 쓰는 두 경로라 한쪽만 느슨하면 그쪽이 다른 쪽의 대기를
     * 늘린다. 이 경로도 같은 어드바이저리 잠금을 잡으므로(아래 javadoc) 그 줄서기를 공유한다.
     *
     * <p><b>값을 복사하지 않고 참조한다.</b> 처음에 {@code = 5} 를 손으로 적고 javadoc 에
     * "같은 값이다" 라고 단정했는데, 그건 {@link ChatKickRecorder} 가 바로 그 사고를 실측으로
     * 적어 둔 모양이다("2초로 줄이면 … 14개 전부 초록"). 한쪽을 고치면 다른 쪽이 조용히 갈린다.
     * 같은 패키지라 상수 참조가 애너테이션의 컴파일 상수 요구를 만족한다.
     */
    static final int TRANSACTION_TIMEOUT_SECONDS = ChatKickRecorder.TRANSACTION_TIMEOUT_SECONDS;

    private final ChatBanStateRepository banStateRepository;
    private final ChatAuditLogRepository auditLogRepository;

    /**
     * 정본에서 밴을 지우고 사면 기준선을 남긴다.
     *
     * <p><b>{@code lockForBanDecision}을 먼저 잡는다.</b> 이 잠금이 지키는 것은 두 가지다. 하나는
     * 아래 미러 변경 번호의 순서이고, 다른 하나는 Postgres 축의 끼어들기다:
     * <pre>
     *   T1(강퇴)  잠금 → append → findActive(없음) → 세기(기준선 없음 → 9명)
     *   R(해제)                            ├─ DELETE chat_ban + INSERT 기준선, 커밋
     *   T1                                 └→ extendOrCreate(ONE_YEAR), 커밋
     * </pre>
     * {@code ChatKickRecorder.record} 는 세기와 쓰기를 <b>둘 다</b> 잠금 안에 두고 커밋까지 쥔다.
     * 해제가 그 잠금을 안 잡으면 다른 테이블만 건드리므로 <b>어느 자원에서도 충돌하지 않고</b>
     * 그 사이에 끼어든다. 결과는 운영자가 방금 접은 누적으로 <b>해제 직후에 1년 밴이 새로
     * 생기는</b> 것이다 — T1 의 세기가 기준선보다 먼저 읽혔기 때문이다.
     *
     * <p>그 창이 열리는 때가 하필 <b>해제를 누르는 가장 현실적인 상황</b>이다: 지금 집중적으로
     * 강퇴당하는 사람을 푸는 것. 잠금을 잡으면 둘 중 하나로 줄 세워지고 둘 다 옳다 — R 이 먼저면
     * T1 의 세기가 기준선을 보고 승격이 없거나 낮아지고, T1 이 먼저면 R 이 방금 생긴 행을 지우고
     * 기준선을 남겨 다음 승격까지 막는다.
     *
     * <p><b>자원 순서</b>: {@code record()} 주석이 "자원이 하나 더 늘면 그 순서를 지키거나 사이클이
     * 없음을 따로 보여야 한다" 고 요구한다. 두 경로의 순서는 이렇다 —
     * <pre>
     *   강퇴  어드바이저리 → chat_kick_log → chat_ban
     *   해제  어드바이저리 → chat_ban → chat_ban_release
     * </pre>
     * 어드바이저리가 <b>맨 앞</b>이라 같은 사용자에 대해 둘이 동시에 뒤 자원을 잡는 일이 없고,
     * 그래서 전순서가 유지된다(사이클 없음). 어드바이저리를 뒤로 옮기거나 해제에서 빼면 그 보장이
     * 사라지고, 어겼다는 사실은 운영에서 {@code 40P01} 로 처음 드러난다.
     *
     * <p><b>미러 축은 번호로 닫는다.</b> 강퇴의 미러 쓰기는 커밋 뒤 잠금 밖이라 해제의 미러 삭제보다
     * 늦게 도착할 수 있다. 그래서 여기서도 잠금 안에서 번호를 집어 돌려주고, 호출자가 그 번호로
     * 지운다 — 미러는 이 번호 이하로 늦게 온 강퇴의 쓰기를 거절한다. 잠금 밖에서 집으면 이 해제와
     * 강퇴의 번호 순서가 커밋 순서와 갈릴 수 있다.
     *
     * <p><b>감사 한 줄이 같은 트랜잭션에 들어간다.</b> 지운 밴의 내용(영구였는지·누가 걸었는지)은
     * 정본 행이 사라지므로 그 행 말고는 어디에도 남지 않는다 —
     * {@code ChatBanStateRepository.release} javadoc 이 이미 "감사 행과 반환값 말고는 어디에도"
     * 라고 적고 있다. 반환값은 응답으로 나가고 사라지므로 남는 쪽은 감사 행뿐이다.
     *
     * <p><b>자원이 하나 늘었다</b>({@code audit_logs}). 순서는 어드바이저리 → {@code chat_ban} →
     * {@code chat_ban_release} → {@code audit_logs} 인데, 사이클이 없는 근거는 순서가 아니라
     * 그 테이블에 겨룰 행이 없다는 것이다({@link ChatKickRecorder#record} javadoc 에 전개해 뒀다).
     *
     * @return 무엇을 지웠는가와 미러 변경 번호. 지울 밴이 없었어도 기준선은 남았다 — 실패가 아니다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Recorded release(UUID targetUserId, UUID actorId, ChatRole actorRole, Instant now) {
        banStateRepository.lockForBanDecision(targetUserId);
        Released released = banStateRepository.release(targetUserId, actorId, now);
        auditLogRepository.append(auditEntry(targetUserId, actorId, actorRole, released, now));
        return new Recorded(released, banStateRepository.nextChangeVersion());
    }

    /**
     * 해제의 결과와, 잠금 안에서 집은 미러 변경 번호.
     *
     * @param mirrorVersion 미러 삭제에 실을 번호({@code ChatBanWriteRepository.releaseMirror})
     */
    public record Recorded(Released released, long mirrorVersion) {
    }

    /**
     * 감사 한 줄. {@code detail} 이 <b>지워진 행의 내용</b>을 싣는다 — 이 셋은 호출 뒤 어디서도
     * 읽을 수 없다.
     *
     * <p>{@code banExisted} 가 거짓이면 "밴 없는 사람에게 버튼을 눌렀다" 이고 그것이 활성 밴을 푼
     * 것과 구분돼야 한다 — 그 호출만으로도 730일 누적이 접히므로 조치가 없었던 것이 아니다.
     * 거짓일 때 나머지 둘은 넣지 않는다(실을 값이 애초에 {@code null} 이다).
     */
    private static ChatAuditEntry auditEntry(UUID targetUserId, UUID actorId, ChatRole actorRole,
                                             Released released, Instant now) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("banExisted", Boolean.toString(released.banExisted()));
        if (released.banExisted()) {
            // 영구 밴은 만료가 없다. 빈칸으로 두면 "기한부인데 값이 빠진" 것과 같아 보이므로 적는다.
            detail.put("expiresAt",
                    released.expiresAt() == null ? "permanent" : released.expiresAt().toString());
            if (released.bannedById() != null) {
                detail.put("bannedById", released.bannedById().toString());
            }
        }
        return new ChatAuditEntry(actorId, actorRole, ChatAuditAction.CHAT_RELEASE_BAN,
                targetUserId, detail, now);
    }
}
