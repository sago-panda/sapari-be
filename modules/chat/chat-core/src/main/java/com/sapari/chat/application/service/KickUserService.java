package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

import com.sapari.chat.application.port.ChatAccountEventPublisher;
import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.domain.exception.ChatKickContendedException;
import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강퇴 등록 — 증거를 박제하고, 명단에 올리고, 모든 Pod에 알린다.
 *
 * <p><b>스테레오타입을 달지 않는다.</b> 이 서비스가 쓰는 저장소가 전부 블로킹(Postgres·Mongo·Redis)이라
 * 리액티브 앱(streaming-app)에는 그 의존이 존재하지 않는다. 컴포넌트 스캔에 걸리면 그 앱이 부팅에
 * 실패하므로, 이 스택을 소유한 앱이 {@code @Bean}으로 직접 등록한다.
 *
 * <p><b>트랜잭션으로 묶지 않는다.</b> 증거 로그 커밋이 확정된 뒤에 Redis와 발행이 일어나야 한다.
 * 한 트랜잭션에 넣으면 커밋 전에 Redis가 먼저 쓰이고, 롤백되면 DB에 없는 강퇴가 Redis에만 남는다.
 * 반대 순서(먼저 커밋, 실패 시 재시도)는 재시도가 안전하다 — 로그는 UNIQUE로 멱등이고 나머지 둘은
 * 같은 값을 다시 쓸 뿐이다.
 *
 * <p><b>클라이언트가 보낸 값으로 권한을 판정하지 않는다.</b> 방 주인과 진행 여부는 live에 묻고, 강퇴자의
 * 역할은 인증된 주체에서, 대상의 역할은 증거 메시지에서 온다. 넷 중 어느 것도 요청 본문에 자리가 없다.
 *
 * <p><b>사용자 계정 저장소에는 닿지 않는다.</b> 역할을 지금 다시 물으려면 이 경로를 얹은 앱이 계정 도메인
 * 전체를 갖게 되고, 그러면 방송 앱이 계정을 고칠 수 있게 된다. 강퇴 하나가 치를 값이 아니다.
 */
@Slf4j
@RequiredArgsConstructor
public class KickUserService implements KickUserUseCase {

    private final GetLiveRoomUseCase liveRoomReader;
    private final ChatMessageEvidenceRepository evidenceRepository;
    private final ChatKickRecorder kickRecorder;
    private final ChatBanWriteRepository banWriteRepository;
    private final ChatKickWriteRepository kickWriteRepository;
    private final ChatKickEventPublisher kickEventPublisher;
    private final ChatAccountEventPublisher accountEventPublisher;
    private final ChatPermissionPolicy permissionPolicy;
    private final TimeProvider timeProvider;

    /**
     * {@inheritDoc}
     *
     * <p>순서에 두 가지 의도가 있다.
     *
     * <p><b>권한 판정이 증거 조회보다 먼저다.</b> 반대로 하면 이 엔드포인트가 "그 messageId가 존재하는가"를
     * 아무에게나 알려주는 조회구가 된다 — 권한 없는 호출자도 응답 차이로 메시지 존재를 훑을 수 있다.
     *
     * <p><b>방 진행 여부 검사도 권한 뒤다.</b> 먼저 보면 권한 없는 호출자가 "이 방이 지금 켜져 있는가"를
     * 알아낼 수 있다. 없는 방과 없는 사용자를 전부 권한 거부로 접는 것도 같은 이유다 — id를 바꿔가며
     * 존재 여부를 세는 통로를 막는다.
     */
    @Override
    public void kick(KickUserCommand command) {
        LiveRoomView room = liveRoomReader.findRoom(command.roomId())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "강퇴할 수 없는 방이다 — roomId=" + command.roomId()));

        ChatRole kickerRole = kickerRole(command.kickerRole());

        // 증거를 읽기 전에 거는 <b>거친</b> 관문이다. 방 주인도 관리자도 아니면 대상이 누구든 결론이 같아서,
        // 대상의 역할을 몰라도 여기서 끝낼 수 있다. 이게 없으면 권한 없는 호출자도 증거 조회까지 도달해
        // 응답 차이로 messageId 존재를 훑는다. 최종 판정은 아래 정책이 한다 — 여기서는 정책을 흉내 내지
        // 않고, 정책이 확실히 거부할 경우만 앞당겨 끊는다.
        if (!room.sellerId().equals(command.kickerId()) && kickerRole != ChatRole.ADMIN) {
            throw new ChatPermissionDeniedException(
                    "강퇴 권한이 없다 — kicker=" + command.kickerId() + " room=" + command.roomId());
        }
        if (!room.live()) {
            throw new LiveNotActiveException(
                    "진행 중인 방이 아니라 강퇴할 수 없다 — roomId=" + command.roomId());
        }

        // 없는 증거를 여기서 끊는다. 아래에서 이 값을 다시 읽으므로, null을 흘려보내고 다른 클래스가
        // 던져 주기를 기대하면 그 계약이 바뀌는 날 여기가 조용히 NPE가 된다.
        // 예외는 불일치와 같은 것을 쓴다 — "없음"과 "안 맞음"을 가르면 messageId 탐색 오라클이 된다.
        ChatMessageEvidence evidence = evidenceRepository.findEvidence(command.messageId())
                .orElseThrow(() -> new ChatKickEvidenceMismatchException(
                        "증거 메시지가 없다 — roomId=" + command.roomId()));
        ChatKickLog kickLog = ChatKickLog.from(evidence, command.roomId(), command.targetUserId(),
                command.kickerId(), kickerRole, timeProvider.now());

        // 대상의 역할은 증거 메시지가 들고 있다. 관리자를 끊지 못하게 하는 데에만 쓰이고, 그 값은
        // 발신 시점에 live가 서명한 룸 토큰에서 왔다.
        if (!permissionPolicy.canKick(kickerRole, command.kickerId(), room.sellerId(),
                evidence.senderRole(), command.targetUserId())) {
            throw new ChatPermissionDeniedException(
                    "강퇴 권한이 없다 — kicker=" + command.kickerId() + " room=" + command.roomId());
        }

        // DB 쓰기는 여기서 끝난다. 이 호출이 반환됐다는 건 커밋이 확정됐다는 뜻이고, 그 다음에야
        // Redis와 발행으로 간다 — 한 트랜잭션에 넣으면 롤백된 강퇴가 Redis에만 남는다.
        // 돌려받은 밴을 그대로 비춘다. 동시 강퇴에서 이 값이 가장 긴 것이 아닐 수 있지만, 미러 쓰기가
        // 늘리기 전용이라 짧은 쪽이 긴 것을 덮지 못한다 — 순서 문제를 순서와 무관한 쓰기로 닫는다.
        Optional<ChatBan> ban = record(kickLog);
        ban.ifPresent(it -> banWriteRepository.ban(
                command.targetUserId(), it.expiresAt(), kickLog.kickedAt()));

        kickWriteRepository.register(command.roomId(), command.targetUserId());
        kickEventPublisher.publishKicked(command.roomId(), command.targetUserId());

        // 두 발행의 순서는 당사자가 무엇을 볼지 정하지 못한다 — 채널이 다르면 연결도 다르고, 수신·처리
        // 순서는 레이스다. 그래도 안전한 이유는 둘 다 안전하고 최종 상태가 같아서다: 종료 사유는 먼저
        // 확정된 것이 이기고(compareAndSet), 닫힌 sink로의 전송은 조용히 버려진다. 밴이 함께 걸린
        // 강퇴는 당사자에게 KICKED 대신 BANNED로 렌더될 수 있고, 그게 더 정확한 값이다
        // ("다른 방으로 가면 되는가"가 두 코드를 가르는 기준이다).
        //
        // 계정 발행은 <b>맨 뒤다.</b> 앞에 두면 이 발행이 실패할 때 그 뒤 문장이 통째로 건너뛰어지는데,
        // 그중 하나가 강퇴 명단 등록이다 — PUBLISH만 실패하는 일시 장애 중에 강퇴가 들어오면 그 방의
        // 강퇴가 아예 성립하지 않고, 밴이 만료되면 그 사람은 강퇴당한 적 없는 사람으로 돌아온다.
        // 이 발행이 하는 일은 "조용히 앉아 있는 세션을 지금 끊는 것"이라 주 집행보다 뒤에 서야 한다.
        //
        // 밴이 새로 걸렸든 이미 있던 것이든 알린다. 이미 있던 경우를 건너뛰면, 그 밴보다 먼저 열려
        // 어떤 이유로든 안 닫힌 세션이 영영 남는다 — 다시 알리면 다음 강퇴가 그걸 치운다.
        // 미러 쓰기가 두 경우 모두에서 도는 것과 같은 이유다.
        ban.ifPresent(it -> accountEventPublisher.publishBanned(command.targetUserId()));
    }

    /**
     * 기록을 남기고, 잠금 대기가 상한을 넘으면 도메인 예외로 바꿔 던진다.
     *
     * <p>번역을 어댑터가 아니라 여기서 하는 이유: 이 타임아웃은 특정 저장소의 성질이 아니라 <b>트랜잭션의
     * 성질</b>이다. 잠금 획득에서도, 강퇴 로그 쓰기에서도, 밴 쓰기에서도 같은 부류가 나오므로 어느 한
     * 어댑터에 두면 다른 쪽이 새고, 양쪽에 두면 같은 판단이 두 벌이 된다. 경계를 여는
     * {@link ChatKickRecorder} 바로 바깥이 그 판단이 한 번만 서는 자리다.
     *
     * <p><b>잠금 쪽이 가장 흔한 출처다.</b> 밴 판단이 사용자 단위로 직렬화되므로 같은 사람을 겹쳐
     * 강퇴하면 뒤에 온 쪽이 트랜잭션 상한보다 먼저 거기서 끊긴다. 그 갈래가 둘이라
     * {@link PessimisticLockingFailureException}으로 묶어 잡는다 — 대기가 제 상한을 넘으면
     * {@code 55P03}에서 {@code CannotAcquireLockException}(실측: Hibernate {@code LockTimeoutException}을
     * 지나 번역, 1초 상한에서 1024ms)이고, 교착이면 {@code 40P01}에서
     * {@code PessimisticLockingFailureException} 자신이다. <b>후자가 전자의 상위</b>라 자식만 열거하면
     * 교착이 그대로 500으로 나간다 — 하필 {@link ChatKickRecorder}가 "어긴 것을 알아채는 시점은
     * 운영에서 40P01이 뜰 때다"라고 적어 둔 그 날, 알아채는 방식이 재시도 가능한 409가 아니라 500이 된다.
     *
     * <p>나머지 둘은 트랜잭션 상한 쪽이다. DB가 문을 취소하면 {@link QueryTimeoutException}이고,
     * 남은 수명이 이미 0 이하라 다음 문을 세우지도 못하면 {@link TransactionTimedOutException}이다
     * (Spring의 {@code ResourceHolderSupport}가 문마다 데드라인을 검사한다). 한쪽만 잡으면 나머지가
     * 500으로 나간다 — 실제로 그렇게 썼다가 잡혔다. 프로브가 앞쪽 갈래만 만들어 냈고 catch가 그 프로브
     * 크기에 맞춰졌다.
     *
     * <p>⚠️ 한때 이 자리에 "{@code CannotAcquireLockException}은 계보가 또 달라 셋을 함께 열거하는
     * 수밖에 없다"고 적혀 있었다. <b>둘 다 틀렸다</b>(javap 실측) — 잠금 계열과
     * {@code QueryTimeoutException}은 {@code TransientDataAccessException}을 공유하고,
     * {@code TransactionTimedOutException}만 그 밖이다. 그래서 그 공통 조상으로 묶으면 둘이 한 번에
     * 잡히기는 한다. <b>그럼에도 그렇게 하지 않는다</b> — 그 조상에는
     * {@code TransientDataAccessResourceException}처럼 "혼잡"이 아니라 "인프라가 흔들렸다"를 뜻하는
     * 갈래도 들어 있어서, 그것까지 409 "잠시 후 다시"로 바꾸면 아래에서 피하려던 바로 그 일 —
     * 울려야 할 쪽이 조용해지는 것 — 이 일어난다. 이 저장소가 그 타입을 쓰는 유일한 자리는 Spring Batch의
     * 재시도 분류({@code UserWithdrawalBatchConfig})이고, 그건 "재시도할 만한가"를 묻는 것이라
     * 넓어도 되는 쓰임이다. HTTP 상태로 옮기는 자리는 다르다.
     *
     * <p><b>상한은 문 하나가 아니라 트랜잭션 전체에 걸린다.</b> 4초짜리 문 둘을 5초 상한에 넣으면 첫
     * 문은 완주하고 둘째가 남은 1초에 끊긴다(실측).
     *
     * <p>⚠️ <b>커밋은 데드라인을 검사하지 않는다.</b> 데드라인을 넘긴 뒤 질의 없이 커밋만 남으면 그대로
     * 성공한다(실측 — 2초 상한에 4초를 흘려보내고 커밋했으나 예외 없음). 한때 이 자리에 "커밋 시점에도
     * 나오므로 프록시 안에서는 못 잡는다"고 적혀 있었는데 <b>사실이 아니다.</b> 두 갈래 모두 본문 안에서
     * 나므로 {@link ChatKickRecorder} 안에서도 잡을 수 있다.
     *
     * <p>⚠️ <b>이 두 타입은 Spring Data 프록시를 지나기 때문에 나온다.</b> 같은 쓰기를 손수 만든
     * {@code EntityManager} 구현으로 옮기면 {@code jakarta.persistence.QueryTimeoutException}이 나와 이
     * catch를 그대로 빠져나가 500이 된다(실측). 오늘은 두 쓰기가 모두 Spring Data 리포지토리를 지나므로
     * 성립하지만, 어댑터를 손수 만든 구현으로 바꾸는 변경은 <b>이 catch를 함께 봐야 한다</b> — 안 보면
     * 아무 테스트도 빨개지지 않는다.
     *
     * <p>그럼에도 바깥에 두는 이유는 둘이다. ① 이 타임아웃은 특정 저장소가 아니라 트랜잭션의 성질이라
     * 강퇴 로그 쓰기에서도 밴 쓰기에서도 나온다 — 어느 한 어댑터에 두면 다른 쪽이 새고, 양쪽에 두면 같은
     * 판단이 두 벌이 된다. ② 여기서 잡는다는 것은 트랜잭션이 <b>이미 되감긴 뒤</b>라는 뜻이라, 취소된
     * 트랜잭션 위에서 무언가를 더 하려는 코드가 자라지 않는다.
     */
    private Optional<ChatBan> record(ChatKickLog kickLog) {   // 이름이 log가 아닌 것은 로거 필드와 겹쳐서다
        try {
            return kickRecorder.record(kickLog);
        } catch (PessimisticLockingFailureException | QueryTimeoutException | TransactionTimedOutException e) {
            // 원인을 여기서 한 번 남긴다. 이 예외는 4xx라 전역 핸들러가 warn 갈래로 보내는데, 그 줄은
            // 코드와 메시지만 찍고 throwable을 넘기지 않는다 — 즉 여기서 안 남기면 원래 예외의 타입도
            // 스택도 어디에도 남지 않는다.
            //
            // 그러면 "동시 강퇴가 겹쳤다"(정상)와 "DB가 느려져 문 하나가 상한을 넘었다"(고장)를 운영자가
            // 구별할 수단이 없다. 두 상황이 같은 타입으로 오기 때문이다. 5xx를 피한 이유는 정상 혼잡이
            // 알림을 울리지 않게 하려는 것이었는데, 원인까지 지우면 울려야 할 쪽도 조용해진다.
            // ⚠️ 이 409는 공짜 재시도가 아니다. 트랜잭션이 되감기므로 <b>그 강퇴의 로그 행도 안 써진다</b> —
            // 그 운영자의 확증 1건이 사라지고, 클라가 재시도하지 않으면 임계가 그만큼 늦게 찬다.
            // 이 저장소에 강퇴 경로 재시도가 없으므로(확인: @Retryable·RetryTemplate 0건) "잠시 후 다시
            // 시도해 주세요"는 문구가 아니라 <b>하중을 받는 계약</b>이다. 지금은 대기 상한을 넘긴 뒤에만
            // 일어나고 임계구역 실측 최악이 2.262ms라 사실상 안 일어나지만, 그 상한을 낮추면 확률이 오른다.
            //
            // 같은 이유로 try-lock(즉시 실패)으로 바꾸지 말 것 — 경합마다 확증을 버리게 된다.
            log.warn("강퇴 기록이 상한을 넘었다 — 경합이면 정상이고, 반복되면 DB가 느려진 것이다."
                    + " targetUserId={}", kickLog.targetUserId(), e);
            throw new ChatKickContendedException(
                    "강퇴 기록이 잠금 대기 상한을 넘었다 — targetUserId=" + kickLog.targetUserId(), e);
        }
    }

    /**
     * 인증 주체의 역할을 채팅 역할로 옮긴다.
     *
     * <p><b>이 값은 클라이언트가 보낸 것이 아니다.</b> 컨트롤러가 인증된 주체에서 채우고, 그 주체는 서명된
     * 토큰에서 나온다. 요청 본문에는 역할을 실을 자리가 없다.
     *
     * <p>모르는 이름은 거부한다. 알 수 없는 역할을 조용히 구매자로 접으면, 나중에 플랫폼에 역할이 하나
     * 늘었을 때 그 사람이 이유 없이 강퇴에 실패하는 것으로만 드러난다.
     */
    private static ChatRole kickerRole(String platformRole) {
        try {
            return toChatRole(UserRole.valueOf(platformRole));
        } catch (IllegalArgumentException e) {
            throw new ChatPermissionDeniedException("알 수 없는 역할이다 — role=" + platformRole);
        }
    }

    /**
     * 플랫폼 역할 → 채팅 역할.
     *
     * <p>{@code default}를 쓰지 않는다. 목록이 늘면 컴파일이 깨져서 알려주도록 세 갈래를 모두 나열한다 —
     * {@code default}로 접으면 새 역할이 조용히 구매자 취급을 받는다.
     */
    private static ChatRole toChatRole(UserRole role) {
        return switch (role) {
            case USER -> ChatRole.BUYER;
            case SELLER -> ChatRole.SELLER;
            case ADMIN -> ChatRole.ADMIN;
        };
    }

    /**
     * 플랫폼 역할 이름 — user 도메인의 열거와 같은 이름을 갖는다.
     *
     * <p>그쪽 타입을 직접 참조하지 않는 이유는 이 경로가 사용자 계정 도메인에 닿지 않기 위해서다.
     * 이름이 갈라지면 위 파싱이 거부로 드러난다.
     */
    private enum UserRole {
        USER, SELLER, ADMIN
    }
}
