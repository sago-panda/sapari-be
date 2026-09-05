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
import java.time.Instant;
import java.time.LocalDate;
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
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;
import com.sapari.global.time.TimeProvider;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

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
    private UserAccountUseCase userAccountReader;
    @Mock
    private ChatMessageEvidenceRepository evidenceRepository;
    @Mock
    private ChatKickLogRepository kickLogRepository;
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
                liveRoomReader, userAccountReader, evidenceRepository, kickLogRepository,
                kickWriteRepository, kickEventPublisher, new ChatPermissionPolicy(),
                new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private KickUserCommand command(UUID kickerId) {
        return new KickUserCommand(roomId, kickerId, targetId, messageId);
    }

    private void givenRoom(boolean live) {
        given(liveRoomReader.findRoom(roomId))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, live, NOW)));
    }

    private void givenUser(UUID userId, UserRole role) {
        givenUser(userId, role, UserStatus.ACTIVE);
    }

    private void givenUser(UUID userId, UserRole role, UserStatus status) {
        given(userAccountReader.findById(userId)).willReturn(Optional.of(new UserView(
                userId, role, status, "닉", NOW, "이름", LocalDate.of(1990, 1, 1),
                UserGender.MALE, "01000000000", null, "u@example.com", UserGrade.BRONZE,
                0, false, ProviderType.KAKAO, "pid", "u@example.com")));
    }

    private void givenEvidence(UUID evidenceRoomId, UUID authorId) {
        given(evidenceRepository.findEvidence(messageId))
                .willReturn(Optional.of(new ChatMessageEvidence(evidenceRoomId, authorId, "문제의 원문")));
    }

    /** 방 주인이 자기 방에서 구매자를 강퇴하는, 전부 통과하는 조합. */
    private void givenHappyPath() {
        givenRoom(true);
        givenUser(sellerId, UserRole.SELLER);
        givenUser(targetId, UserRole.USER);
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
            given(kickLogRepository.appendIfAbsent(any())).willReturn(true);

            // when
            service.kick(command(sellerId));

            // then: 순서가 뒤집히면 롤백된 강퇴가 Redis에만 남는다
            InOrder order = inOrder(kickLogRepository, kickWriteRepository, kickEventPublisher);
            order.verify(kickLogRepository).appendIfAbsent(any());
            order.verify(kickWriteRepository).register(roomId, targetId);
            order.verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("증거는 서버가 읽은 원문이 그대로 박힌다 — 강퇴자가 사유를 지어낼 수 없다")
        void logCarriesServerReadEvidence() {
            // given
            givenHappyPath();
            given(kickLogRepository.appendIfAbsent(any())).willReturn(true);

            // when
            service.kick(command(sellerId));

            // then
            ArgumentCaptor<ChatKickLog> captor = ArgumentCaptor.forClass(ChatKickLog.class);
            verify(kickLogRepository).appendIfAbsent(captor.capture());
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
            given(kickLogRepository.appendIfAbsent(any())).willReturn(false);

            // when
            service.kick(command(sellerId));

            // then: 집행 캐시가 유실된 사용자를 재강퇴로 복구할 수 있어야 한다
            verify(kickWriteRepository).register(roomId, targetId);
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("ADMIN은 남의 방에서도 강퇴한다")
        void adminKicksInAnyRoom() {
            // given: 방 주인이 아닌 관리자
            UUID adminId = UUID.randomUUID();
            givenRoom(true);
            givenUser(adminId, UserRole.ADMIN);
            givenUser(targetId, UserRole.USER);
            givenEvidence(roomId, targetId);
            given(kickLogRepository.appendIfAbsent(any())).willReturn(true);

            // when
            service.kick(command(adminId));

            // then
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }

        @Test
        @DisplayName("탈퇴 유예 중인 대상도 강퇴된다 — REST만 막히고 채팅 세션은 살아 있기 때문이다")
        void withdrawingTargetIsStillKickable() {
            // given
            givenRoom(true);
            givenUser(sellerId, UserRole.SELLER);
            givenUser(targetId, UserRole.USER, UserStatus.WITHDRAWING);
            givenEvidence(roomId, targetId);
            given(kickLogRepository.appendIfAbsent(any())).willReturn(true);

            // when
            service.kick(command(sellerId));

            // then
            verify(kickEventPublisher).publishKicked(roomId, targetId);
        }
    }

    @Nested
    @DisplayName("거부 — 어디서 멈추는가")
    class Rejection {

        /** 거부되면 쓰기 세 단계가 하나도 일어나지 않아야 한다. */
        private void assertNothingWritten() {
            verify(kickLogRepository, never()).appendIfAbsent(any());
            verify(kickWriteRepository, never()).register(any(), any());
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }

        @Test
        @DisplayName("없는 방은 권한 거부다 — '없는 방'과 갈라 주면 방 id를 세는 통로가 된다")
        void unknownRoomIsDeniedNotNotFound() {
            // given
            given(liveRoomReader.findRoom(roomId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("없는 사용자도 권한 거부다 — 같은 이유로 사용자 id도 세지 못하게 한다")
        void unknownUserIsDenied() {
            // given
            givenRoom(true);
            given(userAccountReader.findById(sellerId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("구매자는 강퇴할 수 없다 — 역할은 서버가 읽으므로 클라가 올려 부를 수 없다")
        void buyerCannotKick() {
            // given: 커맨드에는 역할 자리가 없다. 이 값은 전적으로 user 도메인에서 온다
            UUID buyerId = UUID.randomUUID();
            givenRoom(true);
            givenUser(buyerId, UserRole.USER);
            givenUser(targetId, UserRole.USER);

            // when & then
            assertThatThrownBy(() -> service.kick(command(buyerId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("남의 방 판매자는 강퇴할 수 없다")
        void sellerCannotKickInAnotherRoom() {
            // given: 이 방의 주인은 sellerId다
            UUID visitingSeller = UUID.randomUUID();
            givenRoom(true);
            givenUser(visitingSeller, UserRole.SELLER);
            givenUser(targetId, UserRole.USER);

            // when & then
            assertThatThrownBy(() -> service.kick(command(visitingSeller)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("자기 자신은 강퇴할 수 없다")
        void selfKickIsDenied() {
            // given
            givenRoom(true);
            givenUser(sellerId, UserRole.SELLER);
            given(userAccountReader.findById(sellerId))
                    .willReturn(Optional.of(new UserView(
                            sellerId, UserRole.SELLER, UserStatus.ACTIVE, "닉", NOW, "이름",
                            LocalDate.of(1990, 1, 1), UserGender.MALE, "01000000000", null,
                            "u@example.com", UserGrade.BRONZE, 0, false,
                            ProviderType.KAKAO, "pid", "u@example.com")));

            // when & then: 대상이 곧 강퇴자다
            assertThatThrownBy(() -> service.kick(new KickUserCommand(roomId, sellerId, sellerId, messageId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("끝난 방은 거부한다 — 등록하면 아무도 안 보는 명단이 24시간 남는다")
        void endedRoomIsRejected() {
            // given
            givenRoom(false);
            givenUser(sellerId, UserRole.SELLER);
            givenUser(targetId, UserRole.USER);

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(LiveNotActiveException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("⭐ 권한 검사가 증거 조회보다 먼저다 — 아니면 messageId 존재 여부를 아무나 훑는다")
        void permissionIsCheckedBeforeEvidenceLookup() {
            // given: 권한 없는 호출자
            UUID buyerId = UUID.randomUUID();
            givenRoom(true);
            givenUser(buyerId, UserRole.USER);
            givenUser(targetId, UserRole.USER);

            // when
            assertThatThrownBy(() -> service.kick(command(buyerId)))
                    .isInstanceOf(ChatPermissionDeniedException.class);

            // then: 증거 저장소를 건드리지도 않았다
            verify(evidenceRepository, never()).findEvidence(any());
        }

        @Test
        @DisplayName("⭐ 끝난 방 검사도 권한 뒤다 — 아니면 권한 없는 호출자가 방이 켜졌는지 알아낸다")
        void livenessIsCheckedAfterPermission() {
            // given: 권한도 없고 방도 끝났다
            UUID buyerId = UUID.randomUUID();
            givenRoom(false);
            givenUser(buyerId, UserRole.USER);
            givenUser(targetId, UserRole.USER);

            // when & then: 돌아오는 것은 '진행 중이 아님'이 아니라 '권한 없음'이어야 한다
            assertThatThrownBy(() -> service.kick(command(buyerId)))
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
            givenUser(sellerId, UserRole.SELLER);
            givenUser(targetId, UserRole.USER);
            given(evidenceRepository.findEvidence(messageId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickLogRepository, never()).appendIfAbsent(any());
        }

        @Test
        @DisplayName("다른 방 메시지로는 강퇴하지 못한다")
        void evidenceFromAnotherRoomRejects() {
            // given
            givenRoom(true);
            givenUser(sellerId, UserRole.SELLER);
            givenUser(targetId, UserRole.USER);
            givenEvidence(UUID.randomUUID(), targetId);

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickLogRepository, never()).appendIfAbsent(any());
        }

        @Test
        @DisplayName("남이 쓴 메시지로는 강퇴하지 못한다")
        void evidenceByAnotherAuthorRejects() {
            // given
            givenRoom(true);
            givenUser(sellerId, UserRole.SELLER);
            givenUser(targetId, UserRole.USER);
            givenEvidence(roomId, UUID.randomUUID());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(ChatKickEvidenceMismatchException.class);
            verify(kickLogRepository, never()).appendIfAbsent(any());
        }
    }

    @Nested
    @DisplayName("실패 시 뒤 단계가 멈춘다")
    class Propagation {

        @Test
        @DisplayName("로그 저장이 실패하면 명단·발행이 실행되지 않는다 — 재시도가 안전해야 한다")
        void logFailureStopsTheRest() {
            // given
            givenHappyPath();
            willThrow(new IllegalStateException("DB 장애")).given(kickLogRepository).appendIfAbsent(any());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(IllegalStateException.class);
            verify(kickWriteRepository, never()).register(any(), any());
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }

        @Test
        @DisplayName("명단 등록이 실패하면 발행하지 않는다 — 막히지 않은 강퇴를 성공이라 부르지 않는다")
        void registerFailureStopsPublish() {
            // given
            givenHappyPath();
            given(kickLogRepository.appendIfAbsent(any())).willReturn(true);
            willThrow(new IllegalStateException("Redis 장애"))
                    .given(kickWriteRepository).register(any(), any());

            // when & then
            assertThatThrownBy(() -> service.kick(command(sellerId)))
                    .isInstanceOf(IllegalStateException.class);
            verify(kickEventPublisher, never()).publishKicked(any(), any());
        }
    }
}
