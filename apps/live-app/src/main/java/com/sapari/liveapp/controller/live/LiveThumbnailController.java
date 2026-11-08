package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.port.IssueThumbnailUploadUseCase;
import com.sapari.live.view.ThumbnailUploadView;
import com.sapari.liveapp.controller.live.dto.IssueThumbnailUploadRequest;

/**
 * 방 썸네일 업로드 허가(presigned POST). 인가는 기존 {@code /api/v1/lives/**} SELLER 규칙을 그대로 탄다
 * (LiveSecurityConfig 변경 없음). LiveController 와 분리한 것은 방 엔드포인트와 의존성이 겹치지 않아서다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/thumbnails")
public class LiveThumbnailController {

    private final IssueThumbnailUploadUseCase issueThumbnailUploadUseCase;

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEnvelope<ThumbnailUploadView> issueUpload(
            @RequestBody @Valid IssueThumbnailUploadRequest request,
            @CurrentUserId UUID sellerId
    ) {
        return ResponseEnvelope.success(issueThumbnailUploadUseCase.issue(request.toCommand(sellerId)));
    }
}
