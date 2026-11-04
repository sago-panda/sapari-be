package com.sapari.chat.application.service;

import java.util.List;
import java.util.UUID;

import com.sapari.chat.command.ReconcileKickListCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.ReconcileKickListUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강퇴 집행 명단을 정본에서 다시 세운다 — <b>집행 캐시의 역경로</b>다.
 *
 * <p>{@code chat:kicked:{roomId}} 는 재접속을 빠르게 막는 집행 캐시이고 정본은 {@code chat_kick_log}
 * 다. 쓰는 길은 있었다(강퇴마다 {@code SADD}) — 없던 것은 <b>되살리는 길</b>이다. 그래서
 * {@code ChatKickRepository} javadoc 이 "만료라서 복구 가능" 이라 적으면서도 바로 아래에
 * "실제로 되살리는 코드는 아직 없다" 를 붙여 뒀다.
 *
 * <p><b>그 캐시가 사라지면 조용하다.</b> 읽기 경로가 fail-open 이라 아무도 막히지 않고, 그 방의
 * 강퇴자 전원이 돌아오는데 로그 한 줄만 남는다. 두 가지로 사라진다 — 키 축출·replica 승격(정본 L11)
 * 과 우리 타입이 아닌 값으로의 오염({@code KickStoreCorruptedException}). 뒤쪽은 <b>재시도로 낫지
 * 않는다</b>(멱등이면서 영원히 실패) 라서 사람이 오기 전까지 영구히 꺼진 상태다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 정본은 읽기만 하고 쓰기는 Redis 한 번이다 — 경계를 열면
 * 그 안에서 외부 호출을 하는 모양이 되고, 읽기 하나에 경계가 필요하지도 않다.
 *
 * <p><b>명단을 입력으로 받지 않는다</b>({@code ReconcileKickListCommand}). 정본에서 읽으므로
 * 이 경로로는 정본에 없는 사람을 집행 명단에 올릴 수 없다 — 복구 수단이 감사 없는 강퇴 수단이
 * 되지 않게 하는 것이 그 계약의 값이다.
 */
@Slf4j
@RequiredArgsConstructor
public class ReconcileKickListService implements ReconcileKickListUseCase {

    private final ChatPermissionPolicy permissionPolicy;
    private final ChatKickLogRepository kickLogRepository;
    private final ChatKickWriteRepository kickWriteRepository;

    @Override
    public int reconcile(ReconcileKickListCommand command) {
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));
        if (!permissionPolicy.canReconcileKickList(actorRole)) {
            throw new ChatPermissionDeniedException(
                    "강퇴 명단 재적재 권한이 없다 — actor=" + command.actorId() + " role=" + actorRole);
        }

        List<UUID> kicked = kickLogRepository.findKickedUserIds(command.roomId());
        kickWriteRepository.reconcile(command.roomId(), kicked);

        // 재적재는 운영자가 "집행이 꺼져 있다" 를 보고 누르는 것이라, 눌렀다는 사실과 그 결과가
        // 남아야 한다. 0 건도 결과다 — 그 방에 강퇴 기록이 없었다는 뜻이고, 그걸 안 남기면
        // 운영자는 "복구에 실패한 것" 과 "복구할 것이 없던 것" 을 구분할 수 없다.
        log.info("강퇴 명단 재적재 — roomId={} 복원={}명 actor={}",
                command.roomId(), kicked.size(), command.actorId());
        return kicked.size();
    }
}
