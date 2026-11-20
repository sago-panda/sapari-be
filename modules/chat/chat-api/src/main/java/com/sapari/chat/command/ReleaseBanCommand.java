package com.sapari.chat.command;

import java.util.UUID;

/**
 * 밴 해제 명령 (live-app REST).
 *
 * <p><b>요청 본문에서 오는 값이 없다.</b> {@code targetUserId}는 경로에서, {@code actorId}와
 * {@code actorRole}은 <b>인증된 주체</b>에서 온다. 강퇴 명령과 같은 이유다 — 역할을 실을 자리를
 * 만들면 구매자가 자기를 ADMIN이라 적어 남의 제재를 풀 수 있다.
 *
 * <p><b>방 id가 없다.</b> 밴은 방이 아니라 사람에 붙으므로({@code ChatBan}) 어느 방에서 눌렀는지가
 * 판정을 바꾸지 않는다. 그래서 엔드포인트도 방 경로 밖에 있다. 이 자리에 {@code roomId}를 두면
 * 다음 사람이 "자기 방이면 풀 수 있다" 가지를 더하게 되고, 그건 자동 승격이 요구한
 * <b>서로 다른 판매자 N명</b>의 독립성을 한 명이 되돌리는 길이 된다.
 *
 * <p>사유를 받지 않는다. 받으면 자유 텍스트가 감사 기록의 유일한 내용이 되는데, 그건 누른 사람이
 * 지어낼 수 있는 값이다 — 강퇴 증거를 {@code messageId}로 받기로 한 것과 같은 판단이다. 남기는 것은
 * <b>누가 언제</b>이고 그 둘은 서버가 채운다({@code chat_ban_release}).
 *
 * @param actorRole 인증 주체의 역할(플랫폼 역할 이름). <b>요청 본문에서 받지 말 것.</b>
 */
public record ReleaseBanCommand(
        UUID targetUserId,
        UUID actorId,
        String actorRole
) {

    /**
     * 같은 디렉터리의 {@code KickUserCommand} 가 다섯 컴포넌트 전부를 거부하는데 여기만 비어
     * 있었다. 오늘 벡터는 없다 — 유일한 생성자가 컨트롤러이고 세 값이 전부 프레임워크에서 온다.
     * 그래도 채우는 이유는 <b>같은 불변식이 두 레이어에 갈려 있었기</b> 때문이다:
     * {@code actorRole == null} 거부가 커맨드가 아니라 서비스에만 있었다.
     *
     * <p>{@code actorRole} 의 <i>값</i>은 보지 않는다 — 모르는 이름의 판정은
     * {@code ChatRole.fromPlatformRole} 하나가 쥔다. 여기서 또 보면 역할 목록이 두 곳에 생긴다.
     */
    public ReleaseBanCommand {
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId는 필수입니다.");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
    }
}
