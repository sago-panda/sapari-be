package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;
import com.sapari.global.time.TimeProvider;

/**
 * 강퇴 등록의 <b>순서</b>와 <b>어디서 멈추는가</b>를 고정한다.
 *
 * <p>이 서비스에는 값을 계산하는 로직이 거의 없다 — 대신 네 곳에 물어보고, 통과하면 세 곳에 쓴다.
 * 그래서 검증할 것도 "무엇을 반환하는가"가 아니라 <b>거부가 어느 단계에서 일어나고 그 뒤 단계가
 * 실행되지 않는가</b>다. 뒤 단계가 실행되면 DB에 없는 강퇴가 Redis에만 남거나, 권한 없는 호출자가
 * 응답 차이로 남의 데이터 존재를 알아낸다.
 *
 * <p>정책과 시계는 진짜를 쓴다. 정책을 가짜로 바꾸면 이 테스트가 검증하는 권한 판정이 사라지고,
 * 시계는 고정하면 그만이다. 포트만 {@code @Mock}이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KickUserService — 순서가 계약이다")
class KickUserServiceTest {

    @Mock
    private GetLiveRoomUseCase liveRoomReader;
    @Mock
    private ChatMessageEvidenceRepository evidenceRepository;
    @Mock
    private ChatKickRecorder kickRecorder;
    @Mock
    private ChatBanStateRepository banStateRepository;
    @Mock
    private ChatBanWriteRepository banWriteRepository;
    @Mock
    private ChatKickWriteRepository kickWriteRepository;
    @Mock
    private ChatKickEventPublisher kickEventPublisher;

    private KickUserService service;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final String messageId = "68b7f0c2e1a4b93d5c0a1234";

    @BeforeEach
    void setUp() {
        service = new KickUserService(
                liveRoomReader, evidenceRepository, kickRecorder, banStateRepository,
                banWriteRepository, kickWriteRepository, kickEventPublisher,
                new ChatPermissionPolicy(), new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    /** 인증 주체에서 오는 값(kickerId·kickerRole)은 컨트롤러가 채운다 — 요청 본문에는 자리가 없다. */
    private KickUserCommand command(UUID kickerId, String platformRole) {
        return new KickUserCommand(roomId, kickerId, platformRole, targetId, messageId);
    }

    /** 방 주인(판매자)이 강퇴하는, 가장 흔한 조합. */
    private KickUserCommand ownerKick() {
        return command(sellerId, "SELLER");
    }

    private void givenRoom(boolean live) {
        given(liveRoomReader.findRoom(roomId))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, live, NOW)));
    }

    private void givenEvidence(UUID evidenceRoomId, UUID authorId) {
        givenEvidence(evidenceRoomId, authorId, ChatRole.BUYER);
    }

    /** 대상의 역할은 증거 메시지가 들고 있다 — 발신 시점에 룸 토큰이 실어 준 값이다. */
    private void givenEvidence(UUID evidenceRoomId, UUID authorId, ChatRole authorRole) {
        given(evidenceRepository.findEvidence(messageId))
                .willReturn(Optional.of(
                        new ChatMessageEvidence(evidenceRoomId, authorId, authorRole, "문제의 원문")));
    }

    /** 방 주인이 자기 방에서 구매자를 강퇴하는, 전부 통과하는 조합. */
    private void givenHappyPath() {
        givenRoom(true);
        givenEvidence(roomId, targetId);
    }

    @Nested
    @DisplayName("성공 경로")
    class HappyPath {

        @Test
        @DisplayName("로그 → 명단 → 발행 순서로 실행된다 — 커밋이 확정된 뒤에 Redis가 쓰인다")
        void writesInDbFirstOrder() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(ownerKick());

            // then: 순서가 뒤집히면 롤백된 강퇴가 Redis에만 남는다
            InOrder order = inOrder(kickRecorder, kickWriteRepository, kickEventPublisher);
            order.verify(kickRecorder).record(any());
            order.verify(kickWriteRepository).register(roomId, targetId);
            order.verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("증거는 서버가 읽은 원문이 그대로 박힌다 — 강퇴자가 사유를 지어낼 수 없다")
        void logCarriesServerReadEvidence() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(ownerKick());

            // then
            ArgumentCaptor<ChatKickLog> captor = ArgumentCaptor.forClass(ChatKickLog.class);
            verify(kickRecorder).record(captor.capture());
            ChatKickLog log = captor.getValue();
            assertThat(log.triggeringMessage()).isEqualTo("문제의 원문");
            assertThat(log.targetUserId()).isEqualTo(targetId);
            assertThat(log.kickedById()).isEqualTo(sellerId);
            assertThat(log.kickedByRole()).isEqualTo(ChatRole.SELLER);
            assertThat(log.kickedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("중복 강퇴여도 명단 등록과 발행은 실행된다 — 여기서 멈추면 다시는 강퇴하지 못한다")
        void duplicateStillRegistersAndPublishes() {
            // given: 이미 같은 (user, room) 로그가 있어 이번 INSERT는 no-op이다
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(ownerKick());

            // then: 집행 캐시가 유실된 사용자를 재강퇴로 복구할 수 있어야 한다
            verify(kickWriteRepository).register(roomId, targetId);
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("ADMIN은 남의 방에서도 강퇴한다")
        void adminKicksInAnyRoom() {
            // given: 방 주인이 아닌 관리자
            UUID adminId = UUID.randomUUID();
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(command(adminId, "ADMIN"));

            // then
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("탈퇴 유예 중인 대상도 강퇴된다 — 계정 상태를 아예 묻지 않기 때문이다")
        void withdrawingTargetIsStillKickable() {
            // given: 이 경로는 사용자 계정 저장소에 닿지 않는다. 그래서 탈퇴 유예라는 상태가
            // 판정에 끼어들 자리가 없고, 그게 필요한 동작이다 — REST가 막힌 뒤에도 채팅 세션은
            // 살아 있어 그 사람이 방에서 계속 말한다.
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(ownerKick());

            // then
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }
    }

    @Nested
    @DisplayName("거부 — 어디서 멈추는가")
    class Rejection {

        /** 거부되면 쓰기 세 단계가 하나도 일어나지 않아야 한다. */
        private void assertNothingWritten() {
            verify(kickRecorder, never()).record(any());
            verify(kickWriteRepository, never()).register(any(), any());
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }

        @Test
        @DisplayName("없는 방은 권한 거부다 — '없는 방'과 갈라 주면 방 id를 세는 통로가 된다")
        void unknownRoomIsDeniedNotNotFound() {
            // given
            given(liveRoomReader.findRoom(roomId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("모르는 역할 이름은 거부한다 — 조용히 구매자로 접으면 원인이 안 드러난다")
        void unknownRoleNameIsDenied() {
            // given
            givenRoom(true);

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId, "MODERATOR")))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("구매자는 강퇴할 수 없다 — 역할은 인증 주체에서 오므로 올려 부를 수 없다")
        void buyerCannotKick() {
            // given: 요청 본문에는 역할 자리가 없다. 이 값은 서명된 토큰에서 온다
            givenRoom(true);

            // when & then
            assertThatThrownBy(() -> service.kick(command(UUID.randomUUID(), "USER")))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("남의 방 판매자는 강퇴할 수 없다")
        void sellerCannotKickInAnotherRoom() {
            // given: 이 방의 주인은 sellerId다
            givenRoom(true);

            // when & then
            assertThatThrownBy(() -> service.kick(command(UUID.randomUUID(), "SELLER")))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("⭐ 관리자는 강퇴되지 않는다 — 대상 역할은 증거 메시지가 들고 있다")
        void adminTargetIsProtected() {
            // given: 방 주인이 관리자가 남긴 메시지로 강퇴를 건다
            givenRoom(true);
            givenEvidence(roomId, targetId, ChatRole.ADMIN);

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("자기 자신은 강퇴할 수 없다")
        void selfKickIsDenied() {
            // given: 대상이 곧 강퇴자다
            givenRoom(true);
            given(evidenceRepository.findEvidence(messageId)).willReturn(Optional.of(
                    new ChatMessageEvidence(roomId, sellerId, ChatRole.SELLER, "문제의 원문")));

            // when & then
            assertThatThrownBy(() -> service.kick(
                    new KickUserCommand(roomId, sellerId, "SELLER", sellerId, messageId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("끝난 방은 거부한다 — 등록하면 아무도 안 보는 명단이 24시간 남는다")
        void endedRoomIsRejected() {
            // given
            givenRoom(false);

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(LiveNotActiveException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("⭐ 권한 없는 호출자는 증거 조회에 닿지 못한다 — messageId 존재를 훑지 못하게 한다")
        void unauthorizedCallerNeverReachesEvidence() {
            // given
            givenRoom(true);

            // when
            assertThatThrownBy(() -> service.kick(command(UUID.randomUUID(), "USER")))
                    .isInstanceOf(ChatPermissionDeniedException.class);

            // then: 증거 저장소를 건드리지도 않았다
            verify(evidenceRepository, never()).findEvidence(any());
        }

        @Test
        @DisplayName("⭐ 끝난 방 검사도 권한 뒤다 — 아니면 권한 없는 호출자가 방이 켜졌는지 알아낸다")
        void livenessIsCheckedAfterPermission() {
            // given: 권한도 없고 방도 끝났다
            givenRoom(false);

            // when & then: 돌아오는 것은 '진행 중이 아님'이 아니라 '권한 없음'이어야 한다
            assertThatThrownBy(() -> service.kick(command(UUID.randomUUID(), "USER")))
                    .isInstanceOf(ChatPermissionDeniedException.class);
        }
    }
    @Nested
    @DisplayName("증거 정합")
    class Evidence {

        @Test
        @DisplayName("없는 메시지로는 강퇴하지 못한다")
        void missingEvidenceRejects() {
            // given
            givenRoom(true);
            given(evidenceRepository.findEvidence(messageId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickRecorder, never()).record(any());
        }

        @Test
        @DisplayName("다른 방 메시지로는 강퇴하지 못한다")
        void evidenceFromAnotherRoomRejects() {
            // given
            givenRoom(true);
            givenEvidence(UUID.randomUUID(), targetId);

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickRecorder, never()).record(any());
        }

        @Test
        @DisplayName("남이 쓴 메시지로는 강퇴하지 못한다")
        void evidenceByAnotherAuthorRejects() {
            // given
            givenRoom(true);
            givenEvidence(roomId, UUID.randomUUID());

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickRecorder, never()).record(any());
        }
    }

    @Nested
    @DisplayName("밴 미러 — 정본이 돌려준 것을 그대로 비춘다")
    class BanMirror {

        /**
         * 임계 판정과 중복 카운트는 이 서비스의 일이 아니라 {@code ChatKickRecorder}의 일이고, 그쪽은
         * 실제 Postgres로 검증한다({@code ChatKickRecorderTest}). 여기서 고정하는 것은 <b>돌려받은 밴을
         * 미러에 반영하는가</b>와 <b>그 반영이 강퇴 집행보다 먼저인가</b> 둘뿐이다.
         */
        @Test
        @DisplayName("밴이 없으면 미러를 건드리지 않는다")
        void noBanLeavesMirrorAlone() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());

            // when
            service.kick(ownerKick());

            // then
            verify(banWriteRepository, never()).ban(any(), any(), any());
        }

        @Test
        @DisplayName("돌려받은 만료를 그대로 미러에 쓴다 — 정본과 미러가 갈리면 밴이 일찍 풀린다")
        void mirrorsTheReturnedExpiry() {
            // given
            Instant expiry = NOW.plus(Duration.ofDays(7));
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.of(
                    new ChatBan(targetId, UUID.randomUUID(), expiry, NOW)));

            // when
            service.kick(ownerKick());

            // then
            verify(banWriteRepository).ban(targetId, expiry, NOW);
        }

        /**
         * 자동 승격에는 영구가 없지만, 관리자가 손으로 넣은 영구 밴은 여전히 정본에 있을 수 있고
         * {@code findActive}가 그걸 돌려준다. 미러는 그때도 만료 없이 써야 한다.
         */
        @Test
        @DisplayName("사람이 건 영구 밴은 만료 없이 비춘다 — 자동 승격에는 영구가 없다")
        void manualPermanentBanHasNoExpiry() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.of(
                    new ChatBan(targetId, UUID.randomUUID(), null, NOW)));

            // when
            service.kick(ownerKick());

            // then
            verify(banWriteRepository).ban(targetId, null, NOW);
        }

        @Test
        @DisplayName("⭐ 미러가 명단 등록·발행보다 먼저다 — 정본이 확정된 뒤에 집행이 간다")
        void mirrorPrecedesEnforcement() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.of(
                    new ChatBan(targetId, UUID.randomUUID(), NOW.plus(Duration.ofDays(7)), NOW)));

            // when
            service.kick(ownerKick());

            // then
            InOrder order = inOrder(kickRecorder, banWriteRepository,
                    kickWriteRepository, kickEventPublisher);
            order.verify(kickRecorder).record(any());
            order.verify(banWriteRepository).ban(any(), any(), any());
            order.verify(kickWriteRepository).register(roomId, targetId);
            order.verify(kickEventPublisher).publishKicked(roomId, targetId);
        }
    }

    @Nested
    @DisplayName("실패 시 뒤 단계가 멈춘다")
    class Propagation {

        @Test
        @DisplayName("기록이 실패하면 명단·발행이 실행되지 않는다 — 재시도가 안전해야 한다")
        void recordFailureStopsTheRest() {
            // given
            givenHappyPath();
            willThrow(new IllegalStateException("DB 장애")).given(kickRecorder).record(any());

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(IllegalStateException.class);
            verify(kickWriteRepository, never()).register(any(), any());
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }

        @Test
        @DisplayName("명단 등록이 실패하면 발행하지 않는다 — 막히지 않은 강퇴를 성공이라 부르지 않는다")
        void registerFailureStopsPublish() {
            // given
            givenHappyPath();
            given(banStateRepository.findActive(targetId, NOW)).willReturn(Optional.empty());
            willThrow(new IllegalStateException("Redis 장애"))
                    .given(kickWriteRepository).register(any(), any());

            // when & then
            assertThatThrownBy(() -> service.kick(ownerKick()))
                    .isInstanceOf(IllegalStateException.class);
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }
    }
}
