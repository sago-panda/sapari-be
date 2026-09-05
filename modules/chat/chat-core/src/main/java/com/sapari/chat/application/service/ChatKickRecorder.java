package com.sapari.chat.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.ChatBanTier;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강퇴의 <b>DB 쓰기만</b> 한 트랜잭션에 담는 얇은 빈.
 *
 * <p><b>왜 별도 빈인가.</b> 두 쓰기가 모두 `@Modifying` 네이티브 INSERT다. Spring Data는 리포지토리
 * 인터페이스에 직접 선언된 이런 쿼리에 기본 트랜잭션을 얹어 주지 않는다 — 트랜잭션 없이 부르면
 * {@code InvalidDataAccessApiUsageException("No active transaction for update or delete query")}로
 * 실패한다(실측). live의 {@code RtmpIngressAssigner}가 같은 이유로 UPDATE 한 문장만 감싼 것과 같은 처방이다.
 *
 * <p><b>왜 {@code kick()} 전체를 감싸지 않는가.</b> 강퇴는 로그 커밋이 확정된 <i>다음에</i> Redis와 발행으로
 * 가야 한다. 전체를 트랜잭션에 넣으면 커밋 전에 Redis가 먼저 쓰이고, 롤백되면 DB에 없는 강퇴가 Redis에만
 * 남는다. 그래서 <b>DB 쓰기 둘만</b> 여기 담고 Redis는 호출자가 이 메서드가 끝난 뒤에 한다.
 *
 * <p><b>왜 같은 클래스의 메서드가 아닌가.</b> 자기 호출은 프록시를 타지 않아 {@code @Transactional}이
 * 아예 걸리지 않는다. 붙여 놓고 안 걸리는 쪽이 안 붙인 것보다 나쁘다 — 고쳤다고 믿게 된다.
 *
 * <p>스테레오타입을 달지 않는다. 이 스택을 소유한 앱이 {@code @Bean}으로 등록한다.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatKickRecorder {

    /**
     * 누적 강퇴를 세는 창. 2년이 지난 강퇴는 밴 판단에서 빠진다 — 제재는 지금의 행동에 걸어야지
     * 몇 해 전 기록으로 영구히 따라다니면 안 된다.
     */
    private static final Duration KICK_COUNT_WINDOW = Duration.ofDays(730);

    private final ChatKickLogRepository kickLogRepository;
    private final ChatBanStateRepository banStateRepository;

    /**
     * 강퇴를 기록하고, 필요하면 밴으로 올린다. <b>Redis는 건드리지 않는다.</b>
     *
     * <p><b>활성 밴이 있으면 새로 만들지 않는다.</b> 그래야 재시도가 밴을 겹쳐 쌓지 않는다. 그래도 그 밴을
     * 돌려주는 이유는 호출자가 미러를 다시 맞출 수 있게 하기 위해서다 — 키가 사라졌던 사용자도 다음 강퇴에
     * 자동으로 복구된다.
     *
     * <p><b>중복 강퇴는 누적을 세지 않는다.</b> 같은 방 두 번째 강퇴는 정본에 행을 만들지 않으므로 누적도
     * 늘지 않는다. 여기서 굳이 다시 세면 만료된 밴을 같은 카운트로 되살릴 수 있고, 그러면 판매자가 같은
     * 사람을 반복해서 "다시 강퇴"하는 것만으로 밴을 무한히 연장하게 된다.
     *
     * <p>중복 강퇴가 승격을 건너뛰는데도 <b>재시도가 밴을 잃지 않는다.</b> 경계가 생기면서 두 갈래가
     * 모두 막혔기 때문이다 — DB 밴 INSERT가 실패하면 같은 트랜잭션이라 강퇴 로그도 함께 롤백되어 재시도가
     * 처음부터 다시 하고, 경계 밖에서 실패할 수 있는 것은 Redis 미러뿐인데 재시도하면 {@code appendIfAbsent}가
     * {@code false}여도 {@code findActive}가 커밋된 밴을 돌려주어 호출자가 미러를 다시 쓴다. 자가 치유된다.
     *
     * <p><b>시각은 인자로 받지 않는다.</b> 강퇴 로그가 이미 {@code kickedAt}을 들고 있고, 둘을 따로
     * 받으면 갈릴 수 있다 — 갈리는 날 2년 누적 창과 밴 만료가 강퇴 시각과 어긋난다. 호출자가 늘
     * 같은 값을 넘기므로 아무도 그 어긋남을 재현하지 못한다. 출처를 하나로 둔다.
     *
     * @param kickLog 기록할 강퇴(파라미터 이름이 {@code log}가 아닌 것은 로거 필드와 겹치기 때문이다)
     * @return 미러에 반영해야 할 밴(새로 건 것이든 이미 있던 것이든). 밴이 없으면 비어 있다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ChatBan> record(ChatKickLog kickLog) {
        Instant now = kickLog.kickedAt();
        boolean firstKickInThisRoom = kickLogRepository.appendIfAbsent(kickLog);

        Optional<ChatBan> active = banStateRepository.findActive(kickLog.targetUserId(), now);
        if (active.isPresent()) {
            return active;
        }
        if (!firstKickInThisRoom) {
            return Optional.empty();
        }
        long kickCount = kickLogRepository.countSince(kickLog.targetUserId(), now.minus(KICK_COUNT_WINDOW));
        return ChatBanTier.of(kickCount)
                .map(tier -> {
                    ChatBan ban = ChatBan.escalated(kickLog.targetUserId(), tier, now);
                    banStateRepository.append(ban);
                    // 사람이 누른 적 없는 제재라 흔적이 여기밖에 없다. 게다가 지금은 푸는 코드가 없어서
                    // (해제는 행 삭제이고 그걸 하는 경로가 admin-app에 아직 없다) 남기지 않으면 "왜 못
                    // 들어가느냐"는 물음에 chat_ban을 직접 조회해야만 답할 수 있다. 이 도메인이 fail-open
                    // 한 건까지 남기면서 영구 제재를 안 남기는 건 앞뒤가 맞지 않는다.
                    //
                    // 여기가 "새로 걸었다"와 "이미 있었다"를 구분해 아는 유일한 자리다 — 그래서 반환 타입을
                    // 쪼개지 않고도 그 구분이 기록에 남는다. 기존 밴 미러 갱신은 일상이라 남기지 않는다.
                    log.info("자동 밴 승격 — userId={} 누적={}회 단계={} 만료={}",
                            ban.userId(), kickCount, tier, ban.expiresAt());
                    return ban;
                });
    }
}
