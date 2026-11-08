package com.sapari.liveapp.controller.live.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.live.command.IssueThumbnailUploadCommand;

/**
 * 썸네일 업로드 허가 요청. 타입은 정책에 정확히 고정되므로 발급 시점에 받는다(크기는 범위 조건이라 받지 않는다).
 */
public record IssueThumbnailUploadRequest(
        @NotBlank(message = "썸네일 형식은 필수입니다.")
        @Pattern(regexp = "image/(jpeg|png|webp)", message = "썸네일은 JPEG·PNG·WebP만 허용됩니다.")
        String contentType
) {
    public IssueThumbnailUploadCommand toCommand(UUID sellerId) {
        return new IssueThumbnailUploadCommand(sellerId, contentType);
    }
}
