package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.page.CursorPage;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.port.GetReplaysUseCase;
import com.sapari.live.view.ReplaySummaryView;

/**
 * 다시보기 공개 목록(SPR-159). 인증 없음 — {@code LiveSecurityConfig} 공개 GET 목록에 이 경로 하나만 있다.
 * 단건({@code /rooms/{roomId}/replay})은 {@link LiveController} 에 남아 있다.
 *
 * <p>⚠️ <b>경로를 {@code /rooms/{x}} 모양으로 옮기지 말 것.</b> {@code GET /api/v1/lives/rooms/{한 세그먼트}} 는
 * {@code EnterPaths} 가 입장으로 판정해 입장 상한(429)·폐기 조회 장애 503 이 걸린다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives")
public class ReplayListController {

    private final GetReplaysUseCase getReplaysUseCase;

    @GetMapping("/replays")
    public ResponseEnvelope<CursorPage<ReplaySummaryView>> getReplays(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEnvelope.success(getReplaysUseCase.getReplays(new GetReplaysQuery(cursor, size)));
    }
}
