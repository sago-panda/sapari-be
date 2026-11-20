package com.sapari.chat.application.service;

import java.time.Instant;

import com.sapari.chat.command.ReleaseBanCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatRole;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

import com.sapari.chat.domain.exception.ChatKickContendedException;
import com.sapari.chat.domain.repository.ChatBanStateRepository.Released;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.ReleaseBanUseCase;
import com.sapari.chat.port.ReleaseBanUseCase.ReleaseBanResult;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 밴 해제 — 이 도메인에서 <b>처음으로 생긴 역경로</b>다.
 *
 * <p>그전까지 밴은 쓰는 길만 있었다. 자동 승격은 서로 다른 강퇴자 3·6·9명에 1주·1달·1년을 걸고
 * ({@code ChatBanTier}) 영구 밴은 사람이 넣는데, 어느 쪽도 푸는 코드가 없었다 — 기한부는 최소 7일,
 * 최대 1년을 기다려야 했고 영구는 사람이 Redis와 테이블을 직접 만지는 것 말고 길이 없었다.
 *
 * <p><b>세 쓰기가 한 명령이다</b> — 정본 행 삭제 · 사면 기준선 · 미러 삭제. 셋 중 하나만 빠지면
 * 해제가 해제가 아니다:
 * <ul>
 *   <li>미러를 안 지우면 아무도 안 풀린다 — 집행은 {@code chat:banned:} 키가 한다.
 *   <li>기준선을 안 남기면 <b>새 방 강퇴 1회로 같은 티어가 재적용</b>된다. 강퇴 로그는 지우지
 *       않으므로 730일 창에 과거 강퇴자가 그대로 남아 있다
 *       ({@code ChatKickLogRepository.countConfirmingKickersSince}).
 *   <li>행을 안 지우면 다음 자동 승격의 {@code ON CONFLICT} 대상이 남아 발급자·생성시각이 덮인다.
 * </ul>
 *
 * <p><b>순서는 정본 → 미러고, 그 사이가 커밋이다.</b> 반대로 하면 잔여 상태가
 * "정본에 남은 행 + 사라진 미러" 인데 그건 <b>스스로 되살아난다</b> — 남은 행이 다음 강퇴의 조기
 * 반환에서 활성 밴으로 잡혀 미러에 다시 써지고, 운영자는 "풀었는데 다시 막혔다" 를 본다. 이 순서의
 * 잔여 상태(미러만 남은 과다 차단)에는 그런 자기 복원 경로가 없고, 해제를 다시 눌러 수렴한다.
 *
 * <p><b>어긋남은 두 축이고, 닫는 장치가 다르다.</b> Postgres 축은 {@code ChatBanReleaseRecorder}가
 * 밴 판단 잠금을 잡아 닫았다 — 안 잡으면 강퇴가 세기와 쓰기 사이에 해제를 끼워 "방금 접은 누적으로
 * 해제 직후 1년 밴" 을 만든다. 미러 축은 잠금으로 못 닫는다 — 강퇴가 <i>커밋 뒤에</i> 미러를 쓰므로
 * 그 사이에 해제가 끼면 지운 밴이 미러에만 되살아난다. 미러 쓰기를 트랜잭션 안으로 들이면 강퇴의
 * 자가 치유(미러가 날아간 밴을 다음 강퇴에서 되찾는 것)를 잃는다. 그래서 <b>변경 번호</b>로 닫는다:
 * 양쪽이 잠금 안에서 번호를 집고, 미러는 본 것보다 작은 번호의 쓰기를 거절한다.
 *
 * <p><b>해제가 누적까지 접지는 않는다는 오해를 막아 둔다.</b> 접는다. 기준선이 그 일을 하고,
 * 그래서 이 명령은 <b>풀 밴이 없어도</b> 기준선을 남긴다 — 만료가 이미 지난 사람에게 누르는 것도
 * "이 사람의 누적을 접는다" 는 뜻이다.
 */
@Slf4j
@RequiredArgsConstructor
public class ReleaseBanService implements ReleaseBanUseCase {

    private final ChatPermissionPolicy permissionPolicy;
    private final ChatBanReleaseRecorder releaseRecorder;
    private final ChatBanWriteRepository banWriteRepository;
    private final TimeProvider timeProvider;

    @Override
    public ReleaseBanResult release(ReleaseBanCommand command) {
        // 조용히 구매자로 접지 않는다 — 플랫폼에 역할이 하나 늘었을 때 그 사람이 이유 없이
        // 실패하는 것으로만 드러난다.
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));
        if (!permissionPolicy.canReleaseBan(actorRole)) {
            throw new ChatPermissionDeniedException(
                    "밴 해제 권한이 없다 — actor=" + command.actorId() + " role=" + actorRole);
        }

        Instant now = timeProvider.now();
        ChatBanReleaseRecorder.Recorded recorded;
        try {
            // DB 쓰기는 여기서 끝난다. 반환됐다는 것은 커밋이 확정됐다는 뜻이고, 그 다음에야 미러로 간다.
            recorded = releaseRecorder.release(
                    command.targetUserId(), command.actorId(), actorRole, now);
        } catch (PessimisticLockingFailureException | QueryTimeoutException
                 | TransactionTimedOutException e) {
            // 경합을 5xx 로 흘리지 않는다. 이 경로의 복구 수단이 <멱등 재시도>인데, 500 은
            // "재시도해도 소용없다" 는 신호로 읽히고 정상 혼잡이 알림을 울린다 — ChatErrorCode 가
            // 409 를 고른 근거가 그것이고, 번역이 없으면 그 결정이 이 경로에서만 역전된다.
            // 그리고 그 창이 열리는 때가 하필 해제를 누르는 가장 현실적인 상황(집중 강퇴 중)이다.
            // 상한의 값(1s)이 문제가 아니라 실패의 번역이 빠져 있었다 — 강퇴 경로는 같은 세 타입을
            // 같은 예외로 접는다(KickUserService).
            throw new ChatKickContendedException(
                    "밴 해제가 경합으로 실패했다 — 재시도하면 된다(해제는 멱등이다). targetUserId="
                            + command.targetUserId(), e);
        }

        Released released = recorded.released();

        // banExisted 로 게이팅하지 않는다 — 그러면 위 재시도 경로에서 미러에 도달할 코드가 안 남는다.
        // 묻는 것은 번호뿐이다. 잠금 안에서 집은 번호라, 이 해제보다 먼저 커밋된 강퇴가 늦게 미러에
        // 닿아도 그 쓰기가 거절되고, 이 해제보다 나중에 걸린 밴이 먼저 닿았으면 이 삭제가 거절된다.
        //
        // ⚠️ 여기서 실패하면 <정본 행은 지워지고 기준선은 남았는데 미러는 그대로> 다. 그 상태의
        // 피해가 크다: 영구 밴의 미러에는 TTL 이 없어 사람이 다시 누르지 않으면 영원히 과다
        // 차단이다. 그런데 이 호출의 실패는 번역도 로그도 없었다 — 아래 두 로그가 전부 이 줄
        // <뒤>에 있어서, 그 상태가 로그 한 줄 없이 남았고 응답은 일반 500 이었다.
        //
        // 복구 수단은 같은 요청의 멱등 재시도다(위 주석의 그 경로). 그 사실이 응답·로그에
        // 없으면 누른 사람은 재시도할 이유를 모른다. 그래서 잃은 것을 적고, 경합은 위 정본
        // 쓰기와 같은 409 로 접는다 — 같은 조치 안에서 같은 종류의 실패가 다른 등급으로 나가면
        // ChatErrorCode 가 409 를 고른 근거("500 은 재시도해도 소용없다는 신호로 읽힌다")가
        // 이 경로에서만 역전된다.
        //
        // ⚠️ 잡는 타입이 위와 <다르다>. 위는 DB 축이라 셋을 접지만 이 호출의 구현은 Redis 하나뿐
        // 이고(ChatBanWriteRedisRepository) PessimisticLockingFailureException 은 JDBC/JPA 번역에서만
        // 나오므로 여기서는 <도달 불가>였다. 남은 하나는 도달한다 — 실측(spring-data-redis 4.0.5
        // 바이트코드): LettuceExceptionConverter 가 TimeoutException·RedisCommandTimeoutException 을
        // QueryTimeoutException 으로 번역한다. 나머지 Redis 실패는 아래 일반 갈래가 받는다.
        boolean mirrorCleared;
        try {
            mirrorCleared = banWriteRepository.releaseMirror(
                    command.targetUserId(), recorded.mirrorVersion());
        } catch (QueryTimeoutException e) {
            log.error("밴 해제 — 미러 삭제가 경합으로 실패했다. 정본 행은 지워졌고 기준선도 남았는데 "
                            + "미러가 남아 그 사용자는 계속 막힌다(영구 밴은 TTL 이 없어 스스로 "
                            + "풀리지 않는다). 같은 요청을 다시 보내면 된다 — 해제는 멱등이다. "
                            + "targetUserId={} actor={}", command.targetUserId(), command.actorId(), e);
            throw new ChatKickContendedException(
                    "밴 해제의 미러 삭제가 경합으로 실패했다 — 재시도하면 된다(해제는 멱등이다). "
                            + "targetUserId=" + command.targetUserId(), e);
        } catch (RuntimeException e) {
            log.error("밴 해제 — 미러 삭제 실패. 정본 행은 지워졌고 기준선도 남았는데 미러가 남아 "
                            + "그 사용자는 계속 막힌다(영구 밴은 TTL 이 없어 스스로 풀리지 않는다). "
                            + "같은 요청을 다시 보내면 된다 — 해제는 멱등이다. targetUserId={} actor={}",
                    command.targetUserId(), command.actorId(), e);
            throw e;
        }

        if (!mirrorCleared) {
            // 실패가 아니다 — 이 해제보다 뒤에 커밋된 변경(새 밴이거나 다음 해제)이 미러에 먼저 닿았다.
            log.info("밴 해제 — 미러에 이 해제보다 새 변경이 이미 있어 지우지 않았다 targetUserId={} actor={}",
                    command.targetUserId(), command.actorId());
        }

        // 사람이 누른 제재 변경이라 흔적이 chat_ban_release 행과 이 줄뿐이다. 자동 승격이 같은 이유로
        // 로그를 남기고(ChatKickRecorder) 그쪽과 짝이 되게 적는다.
        //
        // 무엇을 지웠는지를 같이 적는다. 정본 행이 사라지므로 영구였는지·누가 걸었는지는 감사 행과
        // 이 줄 말고는 어디에도 없고, 수동 밴 경로가 0건이라 되돌릴 수도 없다.
        if (released.banExisted()) {
            log.info("밴 해제 — targetUserId={} actor={} 지운 밴(만료={} 발급자={}) 누적 사면 기준선={}",
                    command.targetUserId(), command.actorId(),
                    released.expiresAt() == null ? "영구" : released.expiresAt(),
                    released.bannedById(), now);
        } else {
            log.warn("밴 해제 — 풀 밴이 <없었다>. 누적 사면 기준선만 남긴다 targetUserId={} actor={} 기준선={}"
                            + " (대상 id 를 확인할 것 — 이 호출만으로 그 사용자의 730일 누적이 접힌다)",
                    command.targetUserId(), command.actorId(), now);
        }
        // chat-api 는 chat-core 타입을 참조하지 않는다(모듈 규칙) — 경계에서 옮긴다.
        // 발급자 id 는 넘기지 않는다: 그것까지 응답에 실으면 제3자 식별자가 누설된다.
        // 감사가 필요한 쪽은 chat_ban_release 행과 로그가 들고, 응답은 누른 사람이 그 자리에서
        // "풀 밴이 있었는가 · 영구였는가" 를 아는 데까지만 준다.
        return new ReleaseBanResult(released.banExisted(),
                released.banExisted() && released.expiresAt() == null);
    }
}
