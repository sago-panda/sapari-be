package com.sapari.live.application.port;

/**
 * 채팅 입장용 룸 토큰을 발급하는 아웃바운드 포트.
 *
 * <p>live가 개인키로 서명(RS256)하고 chat이 공개키로 검증만 하는 비대칭 모델이다. enter 권위 평가를
 * 통과한 뒤에만 호출되며(라이브 진행중 + owner 판정 완료), 여기서는 서명·직렬화만 담당한다.
 */
public interface RoomTokenIssuer {

    /**
     * claim을 담아 서명된 룸 토큰(JWT 직렬화 문자열)을 반환한다.
     */
    String issue(RoomTokenClaims claims);
}
