package com.sapari.chat.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bson.types.ObjectId;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.domain.exception.ChatHistoryQueryInvalidException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.GetChatHistoryUseCase;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.chat.view.GetChatHistoryView;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 이력 조회 — VOD 다시보기용. <b>블로킹</b>(live-app MVC).
 *
 * <p>세 가지가 여기서 결정된다 — 누가 원문·이메일을 보는가, 강퇴자 메시지를 어떻게 빼는가,
 * 재생 위치를 어떤 시각 구간으로 바꾸는가.
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core 는 리액티브 앱과 블로킹 앱이 함께 의존하는데,
 * 스캔되는 순간 <i>양쪽</i>에 빈이 만들어지려 한다 — 관계형 DB도 동기 Mongo 템플릿도 없는
 * streaming-app 에서는 그 시도가 곧 부팅 실패다. 블로킹 서비스는 그 스택을 실제로 가진 앱이
 * {@code @Bean} 으로 등록한다({@code KickUserService} 와 같은 규율).
 */
@RequiredArgsConstructor
public class GetChatHistoryService implements GetChatHistoryUseCase {

    /** VOD 창 길이 — 재생 위치에서 이만큼 앞까지 본다. */
    private static final Duration PLAYBACK_WINDOW = Duration.ofMinutes(5);

    /** VOD 모드 반환 상한. 커서 모드의 {@code size} 와 달리 클라가 조절하지 못한다. */
    private static final int PLAYBACK_MAX_MESSAGES = 200;

    private final GetLiveRoomUseCase liveRooms;
    private final ChatHistoryRepository historyRepository;
    private final ChatKickLogRepository kickLogRepository;
    private final ChatPermissionPolicy permissionPolicy;

    /**
     * {@code readOnly} + {@code SUPPORTS} — 트랜잭션을 <b>새로 열지 않는다</b>.
     *
     * <p>이 경로의 Postgres 읽기는 강퇴 목록 한 건이라 경계를 새로 열 이유가 없다. 호출자가 이미
     * 트랜잭션 안이면 그걸 따르고({@code SUPPORTS}), 아니면 트랜잭션 없이 돈다 — 지금 호출자
     * (live-app 컨트롤러)가 후자다. 즉 "하나로 묶는다" 가 아니라 "묶여 있으면 따른다" 다.
     *
     * <p>Mongo 읽기는 이 경계 밖이다(다른 저장소다). 그래서 "강퇴 목록을 읽은 시점" 과 "메시지를 읽은
     * 시점" 사이에 새 강퇴가 끼어들 수 있고, 그러면 그 한 요청에서만 그 사람의 메시지가 보인다.
     * 다음 요청에서 사라지므로 수용한다 — 반대 방향(두 저장소를 한 경계로 묶기)은 분산 트랜잭션이고,
     * 얻는 것은 "이미 지나간 방송의 이력 한 페이지" 의 순간 정합성뿐이다.
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public GetChatHistoryView getHistory(GetChatHistoryCommand command) {
        LiveRoomView room = liveRooms.findRoom(command.roomId())
                // 없는 방과 권한 없는 방을 같은 응답으로 답한다 — 가르면 방 id 열거 통로가 열린다.
                // 이력은 공개 조회라 "권한 없음" 자체가 없지만, 없는 방에 빈 목록을 주면 "그 방은
                // 있는데 조용했다" 와 구분되지 않아 그쪽이 더 나쁘다.
                .orElseThrow(() -> new ChatPermissionDeniedException("이력을 조회할 수 없는 방이다"));

        boolean privileged = isPrivileged(command, room);
        Set<UUID> kicked = Set.copyOf(kickLogRepository.findKickedUserIds(command.roomId()));

        List<ChatMessage> messages = command.isPlaybackWindow()
                ? playbackWindow(command, room, kicked)
                : cursorPage(command, kicked);

        return new GetChatHistoryView(messages.stream().map(message -> render(message, privileged)).toList());
    }

    /**
     * 커서 페이지.
     *
     * <p><b>형식이 깨진 커서는 400 이다.</b> 커서는 불투명하지만 클라이언트가 만들어 보내는 값이 아니라
     * 서버가 준 값을 되돌려 보내는 것이므로, 형식이 깨졌다면 클라이언트 코드의 버그다. 빈 목록으로
     * 답하면 프론트가 {@code messages.length === size} 로 다음 페이지를 판단하는 계약 때문에
     * <b>스크롤이 사유 없이 조용히 끝난다</b> — "이력이 더 없다" 와 구분되지 않는다. 그 침묵이 깨진
     * 커서를 만든 버그를 숨긴다.
     *
     * <p>형식 유효성을 응답으로 가르는 것이 탐색 신호가 되지는 않는다 — {@code ObjectId} 가 24자
     * 16진수라는 것은 공개된 형식이고, 클라이언트가 서버에 묻지 않고도 스스로 판정할 수 있다.
     */
    private List<ChatMessage> cursorPage(GetChatHistoryCommand command, Set<UUID> kicked) {
        if (command.beforeId() != null && !ObjectId.isValid(command.beforeId())) {
            throw new ChatHistoryQueryInvalidException("커서 형식이 올바르지 않습니다.");
        }
        return historyRepository.findBefore(command.roomId(), command.beforeId(), command.size(), kicked);
    }

    /**
     * 원문·발신자 이메일을 볼 자격이 있는가.
     *
     * <p><b>판정을 여기서 하지 않는다</b> — 실시간 팬아웃이 묻는 것과 같은 질문이라, 각자 적어 두면
     * 특권 뷰를 받는 역할이 하나 늘 때 한쪽만 따라간다. 실제로 그 위험이 코드에 경고로 적혀 있었다:
     * 소유만 보면 <b>실시간은 관리자에게 열리고 이력은 닫힌다</b>.
     *
     * <p>비회원은 신원이 없어 곧바로 거짓이다 — 역할을 모르는데 특권을 줄 수는 없다.
     */
    private boolean isPrivileged(GetChatHistoryCommand command, LiveRoomView room) {
        if (!command.hasRequesterIdentity()) {
            return false;
        }
        boolean roomOwner = command.requesterId().equals(room.sellerId());
        return permissionPolicy.seesUnmaskedContent(parseRole(command.requesterRole()), roomOwner);
    }

    /**
     * 역할 문자열을 도메인 역할로. 모르는 값은 <b>조용히 좁히지 않고</b> 거부한다.
     *
     * <p>이 값은 컨트롤러가 인증 principal 에서 넣는다. 어긋났다면 역할 열거가 갈라진 것이고, 그때
     * {@code MASKED} 로 조용히 떨어지면 정당한 운영자가 자기 방 이력에서 원문을 못 보는 상태가
     * 버그로 보이지 않는 모양으로 남는다.
     */
    private static ChatRole parseRole(String role) {
        try {
            return ChatRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("요청자 역할을 해석할 수 없다 — role=" + role, e);
        }
    }

    /**
     * 재생 위치 → 절대 시각 구간.
     *
     * <p>경과 시간을 문서에 저장하지 않기로 했으므로({@code createdAt − startedAt} 으로 계산)
     * 변환이 여기 있다. 시작 시각의 출처는 live 다 — chat 은 방송이 언제 시작했는지 모른다.
     *
     * <p>시작하지 않은 방은 매핑할 기준이 없다. 빈 목록이 아니라 <b>거부</b>다 — 빈 목록은
     * "그 구간에 채팅이 없었다" 와 구분되지 않고, 전자는 요청이 틀린 것이다.
     */
    private List<ChatMessage> playbackWindow(GetChatHistoryCommand command, LiveRoomView room,
            Set<UUID> kicked) {

        if (room.startedAt() == null) {
            throw new ChatPermissionDeniedException("시작하지 않은 방은 다시보기 이력이 없다");
        }
        Instant position = room.startedAt().plusMillis(command.playbackPositionMs());
        Instant from = position.minus(PLAYBACK_WINDOW);
        // 재생 위치가 창보다 이르면 구간이 시작 시각 앞으로 간다 — 뒤집히지는 않지만 의미가 없으므로
        // 시작 시각으로 끊는다. 그래야 "영상 1분 지점" 의 창이 [시작, 1분] 이 된다.
        Instant windowStart = from.isBefore(room.startedAt()) ? room.startedAt() : from;
        return historyRepository.findWindow(
                command.roomId(), windowStart, position, PLAYBACK_MAX_MESSAGES, kicked);
    }

    private static ChatMessageView render(ChatMessage message, boolean privileged) {
        return privileged ? message.toPrivilegedView() : message.toView();
    }
}
