package com.sapari.chat.application.service;

import java.time.Instant;

import com.sapari.chat.command.ReleaseBanCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.ReleaseBanUseCase;
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
 * <p>⚠️ <b>어긋남은 두 축인데 하나만 닫힌다.</b> Postgres 축은
 * {@code ChatBanReleaseRecorder}가 밴 판단 잠금을 잡아 닫았다 — 안 잡으면 강퇴가 세기와 쓰기
 * 사이에 해제를 끼워 "방금 접은 누적으로 해제 직후 1년 밴" 을 만든다. <b>미러 축은 열려 있다</b>:
 * 강퇴의 조기 반환이 <i>커밋 뒤에</i> 미러를 다시 쓰므로, 그 사이(밀리초)에 해제가 끼면 지운
 * 밴이 미러에만 되살아난다. 그 쓰기는 설계상 잠금 밖이라 잠금으로 막히지 않고, 막으려면 미러
 * 쓰기를 트랜잭션 안으로 들여야 하는데 그러면 강퇴의 자가 치유(미러가 날아간 밴을 다음 강퇴에서
 * 되찾는 것)를 잃는다. <b>집행 안 되는 밴</b>이 <b>복구 가능한 과다 차단</b>보다 나쁘다고 보고
 * 둔 것이고, 복구 수단은 해제를 다시 누르는 것이다.
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
    public void release(ReleaseBanCommand command) {
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
        // DB 쓰기는 여기서 끝난다. 반환됐다는 것은 커밋이 확정됐다는 뜻이고, 그 다음에야 미러로 간다.
        boolean removed = releaseRecorder.release(command.targetUserId(), command.actorId(), now);

        // 조건 없이 지운다 — removed 로 게이팅하면 위 재시도 경로에서 미러에 도달할 코드가 안 남는다.
        banWriteRepository.releaseMirror(command.targetUserId());

        // 사람이 누른 제재 변경이라 흔적이 chat_ban_release 행과 이 줄뿐이다. 자동 승격이 같은 이유로
        // 로그를 남기고(ChatKickRecorder) 그쪽과 짝이 되게 적는다.
        if (removed) {
            log.info("밴 해제 — targetUserId={} actor={} 누적 사면 기준선={}",
                    command.targetUserId(), command.actorId(), now);
        } else {
            log.info("밴 해제 — 풀 밴이 없었다(멱등). 누적 사면 기준선만 남긴다 targetUserId={} actor={} 기준선={}",
                    command.targetUserId(), command.actorId(), now);
        }
    }
}
