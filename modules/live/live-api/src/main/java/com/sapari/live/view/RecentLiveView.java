package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 최근 본 라이브 목록 한 줄. 방 정보는 조회 시점 값이고 {@code viewedAt} 은 그 회원이 마지막으로 입장한 시각이다.
 *
 * <p><b>재생 주소·룸 토큰·송출 키·송출 유형·미디어 서버 식별자·다시보기 여부를 여기에 더하지 않는다.</b> 재생은 입장
 * 응답의 몫이고, 기록된 방 대부분은 이미 끝난 방이다. {@code status} 는 판매자 목록과 같은 묶음이다 — 기록된 방은
 * 상태 머신상 LIVE·ENDED 이고(입장은 방송 중에만, 취소는 시작 전에만), CANCELLED 는 데이터 정정으로만 생긴다.
 */
public record RecentLiveView(
        UUID roomId,
        UUID sellerId,
        String sellerNickname,
        String title,
        String thumbnailUrl,
        LiveRoomTab status,
        Instant startedAt,
        Instant viewedAt
) {
}
