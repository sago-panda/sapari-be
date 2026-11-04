package com.sapari.chat.domain.model;

import java.util.Optional;

public enum ChatRole {
    BUYER,
    SELLER,
    ADMIN,
    GUEST,
    // 실제 사용자가 아닌 서버 발신 주체 — 입장·퇴장·강퇴·종료 같은 전이성 신호에만 쓴다
    SYSTEM;

    /**
     * 플랫폼 역할 이름 → 채팅 역할. <b>모르는 이름은 빈 값이다.</b>
     *
     * <p>이 값은 클라이언트가 보낸 것이 아니다 — 컨트롤러가 인증된 주체에서 채우고, 그 주체는 서명된
     * 토큰에서 나온다. 요청 본문에 역할을 실을 자리는 어느 경로에도 없다.
     *
     * <p><b>왜 도메인으로 올렸는가.</b> 강퇴와 밴 해제가 같은 파싱을 쓴다. 보안 판정의 입력을 만드는
     * 파싱을 서비스마다 복제하면 한쪽에 역할이 늘 때 다른 쪽이 조용히 뒤처지고, 뒤처진 쪽은
     * "그 사람이 이유 없이 실패한다" 로만 드러난다. 선례는 {@link ChatBanTier#of}다.
     *
     * <p><b>거부를 예외로 던지지 않는다.</b> 거부의 <i>의미</i>가 호출자마다 다르다 — 강퇴는
     * {@code ChatPermissionDeniedException}, 다른 경로는 다른 코드일 수 있다. 도메인이 응답 코드를
     * 고르면 그 결정이 두 번 적히므로 판정만 돌려준다.
     *
     * <p><b>{@code null}이 아니라 {@link Optional}인 이유.</b> 위에서 선례로 든 {@link ChatBanTier#of}가
     * {@code Optional}이고, 같은 모듈에서 같은 모양의 값이 두 표현을 갖지 않아야 한다. 그리고 이
     * 값은 <b>보안 판정의 입력</b>이고 호출자가 둘이다 — 한쪽이 {@code == null} 검사를 빼먹으면
     * {@code switch}에서 NPE 거나, 더 나쁘게는 역할 {@code null}이 어떻게 판정되느냐에 따라 조용히
     * 통과한다. {@code Optional}이면 두 호출자가 그 분기를 잊을 수 없다. 호출 빈도가 REST 경로의
     * 요청당 한 번이라 할당 하나는 재는 값이 아니다(메시지 핫패스가 아니다).
     *
     * <p>{@code default}를 쓰지 않는다. 목록이 늘면 컴파일이 깨져 알려주도록 세 갈래를 모두 나열한다 —
     * {@code default}로 접으면 새 역할이 조용히 구매자 취급을 받는다.
     */
    public static Optional<ChatRole> fromPlatformRole(String platformRole) {
        // null 을 먼저 가른다. valueOf 의 NPE 로 받던 것을 명시로 바꾼 이유는 의도가 드러나는 것과,
        // 넓은 NPE catch 가 나중에 이 블록 안에서 생기는 <다른> NPE 를 삼킬 여지를 없애는 것이다.
        if (platformRole == null) {
            return Optional.empty();
        }
        PlatformRole parsed;
        try {
            parsed = PlatformRole.valueOf(platformRole);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(switch (parsed) {
            case USER -> BUYER;
            case SELLER -> ChatRole.SELLER;
            case ADMIN -> ChatRole.ADMIN;
        });
    }

    /**
     * 플랫폼 역할 이름 — user 도메인의 열거와 같은 이름을 갖는다.
     *
     * <p>그쪽 타입을 직접 참조하지 않는 이유는 이 경로가 사용자 계정 도메인에 닿지 않기 위해서다.
     * 이름이 갈라지면 위 파싱이 거부로 드러난다.
     */
    private enum PlatformRole {
        USER, SELLER, ADMIN
    }
}
