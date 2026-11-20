package com.sapari.live.command;

import java.util.UUID;

/** 최근 본 라이브 목록 요청. {@code cursor} 는 앞 페이지의 {@code nextCursor}(없으면 첫 페이지), {@code size} 는 보정 전 값. */
public record GetRecentLivesQuery(UUID userId, String cursor, Integer size) {
}
