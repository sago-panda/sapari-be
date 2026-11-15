package com.sapari.live.command;

/** 다시보기 공개 목록 요청. {@code cursor} 는 앞 페이지의 {@code nextCursor}(없으면 첫 페이지), {@code size} 는 보정 전 값. */
public record GetReplaysQuery(String cursor, Integer size) {
}
