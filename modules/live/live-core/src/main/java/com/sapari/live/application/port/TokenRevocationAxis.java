package com.sapari.live.application.port;

/**
 * 폐기가 어느 축에서 적중했는가. <b>대응이 다르다.</b>
 *
 * <p>{@link #JTI} 는 <b>그 토큰 한 장</b>이다 — 닉네임 변경 후 재발급처럼 정상 운영에서도 생긴다.
 * {@link #SID} 는 <b>그 로그인 세션 전부</b>라 로그아웃·탈퇴·refresh 재사용 탐지에서 온다. 후자가
 * 몰려서 오르면 대량 로그아웃이거나 토큰 탈취 대응이 돌고 있다는 뜻이고, 전자는 그냥 일상이다.
 *
 * <p>둘을 한 수로 합치면 그 구분이 사라진다 — {@code live.enter.rejected} 의 {@code gate} 태그를
 * 가른 것과 같은 이유다.
 */
public enum TokenRevocationAxis {
    JTI,
    SID
}
