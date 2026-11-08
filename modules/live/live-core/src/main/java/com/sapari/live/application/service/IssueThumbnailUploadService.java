package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.PresignedPost;
import com.sapari.live.application.port.ThumbnailUploadSigner;
import com.sapari.live.command.IssueThumbnailUploadCommand;
import com.sapari.live.domain.model.ThumbnailKey;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.port.IssueThumbnailUploadUseCase;
import com.sapari.live.view.ThumbnailUploadView;

/**
 * 썸네일 업로드 허가 발급. 바이트는 서버를 지나지 않는다 — 클라이언트가 저장소로 직접 올리고, 인프라 검증(별도 티켓)이
 * 내용을 확인해 검증 완료 영역으로 옮긴다. 트랜잭션·외부 호출 없음(서명은 로컬 계산).
 */
@Service
@RequiredArgsConstructor
public class IssueThumbnailUploadService implements IssueThumbnailUploadUseCase {

    private final ThumbnailUploadSigner thumbnailUploadSigner;
    private final ThumbnailUrlRule thumbnailUrlRule;
    private final TimeProvider timeProvider;

    @Override
    public ThumbnailUploadView issue(IssueThumbnailUploadCommand command) {
        ThumbnailKey key = new ThumbnailKey(command.sellerId(), UUID.randomUUID());
        PresignedPost post = thumbnailUploadSigner.sign(key.uploadKey(), command.contentType(), timeProvider.now());
        return new ThumbnailUploadView(post.url(), post.fields(), post.expiresAt(), thumbnailUrlRule.publicUrlOf(key));
    }
}
