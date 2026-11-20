package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.PresignedPost;
import com.sapari.live.application.port.ThumbnailUploadSigner;
import com.sapari.live.command.IssueThumbnailUploadCommand;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.view.ThumbnailUploadView;

@DisplayName("IssueThumbnailUploadService — 업로드 영역 key 로 서명하고 같은 객체의 검증 완료 주소를 돌려준다")
class IssueThumbnailUploadServiceTest {

    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final ThumbnailUploadSigner signer = mock(ThumbnailUploadSigner.class);
    private final TimeProvider timeProvider = mock(TimeProvider.class);
    private final ThumbnailUrlRule rule = ThumbnailUrlRule.of("https://cdn.example.com");
    private final IssueThumbnailUploadService service = new IssueThumbnailUploadService(signer, rule, timeProvider);

    @Test
    void issues() {
        given(timeProvider.now()).willReturn(NOW);
        given(signer.sign(anyString(), anyString(), any())).willReturn(
                new PresignedPost("https://b.s3.r.amazonaws.com/", Map.of("policy", "p"), NOW.plusSeconds(600)));

        ThumbnailUploadView view = service.issue(new IssueThumbnailUploadCommand(SELLER, "image/png"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(signer).should().sign(key.capture(), eq("image/png"), eq(NOW));
        assertThat(key.getValue()).matches("uploads/thumbnails/" + SELLER + "/[0-9a-f-]{36}");
        String objectId = key.getValue().substring(key.getValue().lastIndexOf('/') + 1);

        assertThat(view.thumbnailUrl()).isEqualTo("https://cdn.example.com/thumbnails/" + SELLER + "/" + objectId);
        assertThatCode(() -> rule.verify(view.thumbnailUrl(), SELLER)).doesNotThrowAnyException();
        assertThat(view.uploadUrl()).isEqualTo("https://b.s3.r.amazonaws.com/");
        assertThat(view.fields()).containsEntry("policy", "p");
        assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }
}
