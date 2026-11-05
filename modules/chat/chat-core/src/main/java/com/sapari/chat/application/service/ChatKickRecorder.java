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
import com.sapari.chat.domain.repository.ChatBanStateRepository.BanWrite;
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
     * 확증을 세는 창. 2년이 지난 강퇴는 밴 판단에서 빠진다 — 제재는 지금의 행동에 걸어야지
     * 몇 해 전 기록으로 영구히 따라다니면 안 된다.
     */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofDays(730);

    /**
     * 이 트랜잭션 전체의 상한(초).
     *
     * <p><b>잠금 대기의 상한이 아니다.</b> 그것은 어댑터가 따로 건다({@code ChatBanJpaRepository}의
     * 대기 상한) — 대기자가 기다리는 동안에도 커넥션을 쥐므로, 그 비용을 이 값으로 막으려 하면
     * 너무 길다. 전에는 이 자리에 "길게 잡으면 커넥션을 쥔 줄이 길어진다"는 근거가 적혀 있었는데,
     * 그 걱정은 이제 대기 상한이 받는다.
     *
     * <p>남은 역할은 <b>트랜잭션 전체가 무한히 돌지 않게</b> 하는 것이다. 정상 경로는 밀리초라
     * (임계구역 실측 avg 0.186ms / max 2.262ms) 이 값에 닿지 않는다. 5초는 고른 값이지 측정된
     * 값이 아니다.
     *
     * <p>package-private 인 것은 테스트가 읽기 때문이다. 손으로 복사해 두면 이 값을 줄일 때
     * "대기가 유계다"를 지키는 단언이 <b>조용히 죽는다</b>(실측: 2초로 줄이면 상한 제거 뮤턴트의
     * 대기가 그 사본 5초 미만을 통과해 14개 전부 초록이 된다).
     */
    static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final ChatKickLogRepository kickLogRepository;
    private final ChatBanStateRepository banStateRepository;

    /**
     * 강퇴를 기록하고, 필요하면 밴으로 올린다. <b>Redis는 건드리지 않는다.</b>
     *
     * <p><b>활성 밴이 있으면 새로 만들지 않는다.</b> 그래야 재시도가 밴을 겹쳐 쌓지 않는다.
     *
     * <p><b>미러에 반영할 밴을 돌려준다</b> — 새로 건 것이든 이미 있던 것이든. 후자도 돌려주는 이유는
     * 미러 키가 사라졌던 사용자가 다음 강퇴에서 되찾을 수 있어야 해서다.
     *
     * <p>동시 강퇴에서 이 값이 <b>가장 긴 밴이 아닐 수 있다</b>. 그래도 해롭지 않은 것은 미러 쓰기가
     * 늘리기 전용이어서다 — 짧은 쪽이 나중에 도착해도 긴 것을 덮지 못한다. 그 보장이 없다면 여기서
     * 정본을 다시 읽어야 하고, 실제로 그렇게 해 봤지만 <b>레이스를 좁힐 뿐 닫지 못했다</b>(무엇을 쓸지는
     * 고쳐도 누가 마지막에 쓰는지는 그대로다). 순서 문제는 순서와 무관한 쓰기로 닫는 것이 맞다.
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
     * <p><b>이 트랜잭션이 잠그는 자원과 순서: 사용자 어드바이저리 → {@code chat_kick_log(user, room)}
     * → {@code chat_ban(user)}.</b> 데드락이 없는 근거가 어드바이저리 잠금을 들인 뒤로 달라졌다. 전에는
     * {@code chat_ban}에 쓰는 경로가 이 메서드 하나뿐이라 모든 트랜잭션이 <i>우연히</i> 같은 순서로
     * 잠그는 것에 기대고 있었다. 지금은 <b>어드바이저리가 언제나 첫 자원</b>이고 같은 사용자에 대해
     * 한 트랜잭션만 그 뒤로 넘어가므로, 같은 사용자끼리는 뒷 자원을 두고 겹칠 수 없고 다른 사용자끼리는
     * 뒷 자원이 애초에 갈린다({@code chat_ban}은 사용자당 한 행, 강퇴 로그는 (사용자, 방) 한 행).
     * 전순서가 서니 사이클이 생기지 않는다.
     *
     * <p>자원이 하나 더 늘면 그 순서를 지키거나 사이클이 없음을 따로 보여야 한다 — 어긴 것을 알아채는
     * 시점은 운영에서 {@code 40P01}이 뜰 때다.
     *
     * @param kickLog 기록할 강퇴(파라미터 이름이 {@code log}가 아닌 것은 로거 필드와 겹치기 때문이다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<ChatBan> record(ChatKickLog kickLog) {
        Instant now = kickLog.kickedAt();
        // 승격 판단 전체를 사용자 단위로 줄 세운다. 세기·견주기·쓰기가 세 걸음이고 그 사이에 남의
        // 미커밋 강퇴가 보이지 않으므로(READ COMMITTED), 직렬화하지 않으면 서로 다른 방의 두 강퇴가
        // 각자 "기존 + 나"만 세고 둘 다 임계 미달로 판단해 합쳐서 임계를 넘겼는데 아무도 쓰지
        // 않는다. 쓰기 규칙(단조 upsert)으로는 닿을 수 없는 자리다 — 그쪽은 둘 다 쓸 때의 순서를
        // 다루고, 여기서 잃는 것은 아무도 쓰지 않는 경우다.
        //
        // 기록보다 먼저 잡는다. 세는 걸음만 감싸도 결과는 같지만(뒤에 온 쪽이 앞의 커밋을 보고
        // 다시 센다) 그러면 임계구역의 시작이 이 메서드 중간이 되어, 이 트랜잭션이 무엇을 혼자 하는지가
        // 한 문장으로 안 읽힌다. 첫 문장에서 잡으면 record() 전체가 사용자 단위 임계구역이다.
        //
        // 그 선택의 대가는 아래 조기 반환 둘이다 — 활성 밴이 이미 있거나 같은 방 중복 강퇴라
        // 쓸 것이 없는 게 확실한데도 전 구간 잠금을 쥔다. 차가운 경로인 것은 맞다(활성 밴이 있으면
        // Redis 미러가 애초에 그 사람의 전송을 막으므로 강퇴가 다시 올 일이 드물다). 세는 걸음만
        // 감싸면 그 둘은 잠금을 안 잡지만, 위에 적은 가독성을 잃는다.
        banStateRepository.lockForBanDecision(kickLog.targetUserId());

        // 이 뒤로 잡히는 잠금은 둘이다 — 강퇴 로그의 (user, room) 유니크와 밴 쓰기의 사용자 단위
        // 유니크. 같은 사용자에 대해서는 위 어드바이저리를 통과한 트랜잭션이 하나뿐이라 여기서 겹치지
        // 않고, 다른 사용자끼리는 두 자원이 애초에 갈린다.
        //
        // 잠금을 두는 것 자체의 대가는 따로 있다(위 조기 반환과는 다른 축이다) — 방이 달라도
        // 줄을 선다. 받아들이는 근거: 경계 안에 외부 호출이 없고 잠금 뒤에는 쿼리 셋과 커밋뿐이라
        // 정상 보유 시간이 밀리초다.
        //
        // 줄서기 자체의 상한은 어댑터가 건다 — 기다리는 쪽도 커넥션을 쥐므로 트랜잭션 상한으로는
        // 너무 늦다. 이 트랜잭션의 상한은 그와 별개로 "전체가 무한히 돌지 않게"를 맡는다. 둘 다
        // 있어야 실패가 '대기'가 아니라 '재시도할 수 있는 예외'가 된다.
        boolean firstKickInThisRoom = kickLogRepository.appendIfAbsent(kickLog);

        Optional<ChatBan> active = banStateRepository.findActive(kickLog.targetUserId(), now);
        if (active.isPresent()) {
            return active;
        }
        if (!firstKickInThisRoom) {
            return Optional.empty();
        }
        long kickers = kickLogRepository.countConfirmingKickersSince(
                kickLog.targetUserId(), now.minus(CONFIRMATION_WINDOW));
        return ChatBanTier.of(kickers)
                .map(tier -> {
                    BanWrite write = banStateRepository.extendOrCreate(
                            ChatBan.escalated(kickLog.targetUserId(), tier, now));
                    // 사람이 누른 적 없는 제재라 흔적이 여기밖에 없다. 남기지 않으면 "왜 못
                    // 들어가느냐"는 물음에 chat_ban을 직접 조회해야만 답할 수 있다. 이 도메인이 fail-open
                    // 한 건까지 남기면서 제재를 안 남기는 건 앞뒤가 맞지 않는다.
                    //
                    // (여기에 "지금은 푸는 코드가 없다 — 해제 경로가 admin-app에 아직 없다"가 적혀
                    //  있었다. 둘 다 거짓이 됐다: ReleaseBanService 가 풀고, 호스트는 admin-app이
                    //  아니라 live-app이다. 그 앱은 파일 3개에 modules 의존이 0건인데 live-app은
                    //  인증 주체·역할·chat-core 배선·ChatException 처리기가 이미 돌고 있어서,
                    //  해제 엔드포인트의 증분이 앱 부트스트랩보다 작다. 설계 정본은 수동 밴·해제를
                    //  admin-app 소관으로 적었고 이 선택은 그와 어긋난다 — 운영자 조치를 운영자 앱에
                    //  모으려면 그때 옮긴다.)
                    //
                    // 두 경우를 갈라 적는다. 안 갈랐을 때는 동시 강퇴에서 남의 트랜잭션이 건 만료가 이
                    // 호출의 단계와 나란히 찍혀, 단계와 만료가 서로 맞지 않는 기록이 됐다 —
                    // "단계=ONE_WEEK 만료=<1년 뒤>" 같은 줄이다. 게다가 아무 행도 쓰지 않은 요청이
                    // "승격"으로 남았다.
                    if (write.applied()) {
                        log.info("자동 밴 승격 — userId={} 확증={}명 단계={} 만료={}",
                                kickLog.targetUserId(), kickers, tier, write.effective().expiresAt());
                    } else {
                        log.info("자동 밴 승격 생략 — 이미 더 긴 밴이 있다 userId={} 확증={}명 이 단계={} 남은 만료={}",
                                kickLog.targetUserId(), kickers, tier, write.effective().expiresAt());
                    }
                    return write.effective();
                });
    }
}
