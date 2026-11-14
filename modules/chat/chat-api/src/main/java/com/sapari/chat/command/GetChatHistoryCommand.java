package com.sapari.chat.command;

import java.time.Duration;
import java.util.UUID;

/**
 * 채팅 이력 조회 입력 (live-app REST).
 *
 * <p>세 가지 조회 모드가 있고 <b>서로 배타다</b> — 과거 방향 커서({@code beforeId}), 재접속 커서
 * ({@code afterId} — 마지막으로 받은 메시지 이후), VOD 윈도우({@code playbackPositionMs}). 한쪽을
 * 조용히 무시하면 클라가 보낸 의도와 다른 결과를 받고도 그 사실을 알 수 없으므로 즉시 거부한다.
 *
 * <p><b>요청자는 없을 수 있다.</b> 다시보기 채팅은 비회원도 본다(설계 확정). 그래서 {@code requesterId}
 * 가 필수가 아니고, 없으면 특권 뷰(원문·발신자 이메일)를 받지 못할 뿐이다 — 그 판정은 서비스가 한다.
 * 필수로 두면 비회원 경로에서 컨트롤러가 채울 값이 없어 공개 조회 자체가 불가능해진다.
 *
 * <p><b>{@code requesterRole} 이 함께 필요한 이유</b>: 특권 판정이 소유만으로 끝나지 않는다. 실시간
 * 팬아웃은 방 주인과 <b>관리자</b>에게 같은 것을 주기로 제품 결정이 돼 있어서, 이력이 소유만 보면
 * 관리자가 이력에서만 빠진다 — 같은 질문에 두 답이 생긴다. 판정은
 * {@code ChatPermissionPolicy.seesUnmaskedContent} 한 자리에서 한다.
 */
public record GetChatHistoryCommand(
        UUID roomId,
        UUID requesterId,          // nullable — 비회원 조회. 있으면 requesterRole 도 있어야 한다
        String requesterRole,      // nullable — ChatRole 이름. requesterId 와 쌍이다
        String beforeId,           // 과거 방향 커서(nullable) — 나머지 두 모드와 배타
        String afterId,            // 재접속 커서(nullable) — 나머지 두 모드와 배타
        Long playbackPositionMs,   // VOD 위치(nullable) — 나머지 두 모드와 배타
        int size                   // 페이지 크기(커서 모드 전용 — VOD 모드는 200 고정)
) {
    /**
     * 재생 위치 상한.
     *
     * <p>메시지는 2년 뒤 TTL 로 사라지므로 그보다 먼 위치의 창은 <b>증명적으로 비어 있다</b> — 상한을
     * 넘은 요청은 "결과가 없는 것" 이 아니라 입력이 틀린 것이다. 방송 길이로 자르는 쪽이 더 정확하지만
     * 방 뷰에 종료 시각이 없어(live 계약) 여기서 잴 수 없다.
     *
     * <p>상한이 없으면 극단값이 그대로 시각 연산으로 간다. <b>실측</b>: {@code Instant.plusMillis} 자체는
     * 터지지 않고 연도 {@code +292279051} 을 돌려주지만, 그 값을 Mongo 로 보낼 때
     * {@code Instant.toEpochMilli()} 가 {@code ArithmeticException: long overflow} 를 던진다 — 이
     * 엔드포인트는 무인증 공개라 파라미터 하나로 500 을 만들 수 있다.
     */
    private static final long MAX_PLAYBACK_POSITION_MS = Duration.ofDays(730).toMillis();

    public GetChatHistoryCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        // 신원은 둘이 한 쌍이다. 한쪽만 오면 특권 판정이 반쪽이 되는데, 그 반쪽은 <b>조용히 좁은
        // 쪽으로</b> 기울어 요청자가 자기 방 이력에서 원문을 못 보는 모양이 된다 — 버그로 보이지 않는
        // 버그다. 쌍이 깨진 입력은 컨트롤러의 잘못이므로 여기서 거부한다.
        if (requesterId != null && (requesterRole == null || requesterRole.isBlank())) {
            throw new IllegalArgumentException("requesterId가 있으면 requesterRole도 필요합니다.");
        }
        if (requesterId == null && requesterRole != null && !requesterRole.isBlank()) {
            throw new IllegalArgumentException("requesterRole이 있으면 requesterId도 필요합니다.");
        }
        if (beforeId != null && playbackPositionMs != null) {
            throw new IllegalArgumentException("beforeId와 playbackPositionMs는 동시에 지정할 수 없습니다.");
        }
        if (afterId != null && (beforeId != null || playbackPositionMs != null)) {
            throw new IllegalArgumentException("afterId는 beforeId·playbackPositionMs와 동시에 지정할 수 없습니다.");
        }
        // 음수 위치는 창을 재생 시작 앞으로 보내 조용히 빈 결과가 된다 — 빈 이력과 구분되지 않는다.
        if (playbackPositionMs != null && playbackPositionMs < 0) {
            throw new IllegalArgumentException("playbackPositionMs는 0 이상이어야 합니다.");
        }
        if (playbackPositionMs != null && playbackPositionMs > MAX_PLAYBACK_POSITION_MS) {
            throw new IllegalArgumentException(
                    "playbackPositionMs는 " + MAX_PLAYBACK_POSITION_MS + " 이하여야 합니다: " + playbackPositionMs);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size는 1~100 범위여야 합니다.");
        }
    }

    /**
     * {@code afterId} 가 없는 생성자 — live-app 컨트롤러가 지금 이걸로 짓는다.
     *
     * <p>남기는 이유: 컨트롤러는 live-app 파일이라 chat 변경과 같은 커밋에 고칠 수 없다. 이 생성자가
     * 없으면 chat 이 먼저 머지되는 순간 live-app 컴파일이 깨진다. 컨트롤러가 {@code afterId} 를 넘기게
     * 되면 지워도 된다.
     */
    public GetChatHistoryCommand(UUID roomId, UUID requesterId, String requesterRole, String beforeId,
            Long playbackPositionMs, int size) {
        this(roomId, requesterId, requesterRole, beforeId, null, playbackPositionMs, size);
    }

    /** 특권 뷰 판정에 쓸 신원이 있는가 — 비회원이면 없다. */
    public boolean hasRequesterIdentity() {
        return requesterId != null;
    }

    /** VOD 윈도우 모드인가. */
    public boolean isPlaybackWindow() {
        return playbackPositionMs != null;
    }

    /** 재접속 모드인가 — 마지막으로 받은 메시지 이후를 묻는다. 둘 다 아니면 과거 방향 커서 모드다. */
    public boolean isAfterCursor() {
        return afterId != null;
    }
}
