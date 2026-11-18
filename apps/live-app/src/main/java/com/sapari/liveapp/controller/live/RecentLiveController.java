package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.page.CursorPage;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.port.GetRecentLivesUseCase;
import com.sapari.live.view.RecentLiveView;

/**
 * 회원의 최근 본 라이브(SPR-172). 구매자도 쓰는 회원 경로라 {@code LiveSecurityConfig} 에 SELLER 바닥보다 먼저 오는
 * 인증 규칙이 있다.
 *
 * <p>⚠️ <b>경로를 {@code /rooms/{x}} 모양으로 옮기지 말 것.</b> {@code GET /api/v1/lives/rooms/{한 세그먼트}} 는
 * {@code EnterPaths} 가 입장으로 판정해 입장 상한(429)·폐기 조회 장애 503 이 걸리고, 공개 입장 규칙에도 맞는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives")
public class RecentLiveController {

    private final GetRecentLivesUseCase getRecentLivesUseCase;

    @GetMapping("/recent")
    public ResponseEnvelope<CursorPage<RecentLiveView>> getRecent(
            @CurrentUserId UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEnvelope.success(getRecentLivesUseCase.getRecent(new GetRecentLivesQuery(userId, cursor, size)));
    }
}
