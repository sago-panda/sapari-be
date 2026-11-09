package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.LiveErrorCode;

@DisplayName("ThumbnailUrlRule — CDN 호스트 + 검증 완료 영역 + 본인 경로만 통과")
class ThumbnailUrlRuleTest {

    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String OBJ = "44444444-4444-4444-4444-444444444444";
    private static final String OK = "https://cdn.example.com/thumbnails/" + SELLER + "/" + OBJ;

    private final ThumbnailUrlRule rule = ThumbnailUrlRule.of("https://cdn.example.com");

    @Test
    void accepts() {
        assertThatCode(() -> rule.verify(OK, SELLER)).doesNotThrowAnyException();
        assertThatCode(() -> rule.verify("https://CDN.Example.com/thumbnails/" + SELLER + "/" + OBJ, SELLER))
                .doesNotThrowAnyException();
    }

    @Test
    void basePathIsHonoured() {
        ThumbnailUrlRule withPath = ThumbnailUrlRule.of("https://cdn.example.com/assets/");
        String url = "https://cdn.example.com/assets/thumbnails/" + SELLER + "/" + OBJ;

        assertThatCode(() -> withPath.verify(url, SELLER)).doesNotThrowAnyException();
        assertRejected(withPath, OK, SELLER);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.com/thumbnails/{S}/{O}",
            "https://other.com/thumbnails/{S}/{O}",
            "https://cdn.example.com.evil.com/thumbnails/{S}/{O}",
            "https://user@cdn.example.com/thumbnails/{S}/{O}",
            "https://cdn.example.com:8443/thumbnails/{S}/{O}",
            "https://cdn.example.com/thumbnails/{S}/{O}?a=1",
            "https://cdn.example.com/thumbnails/{S}/{O}#f",
            "https://cdn.example.com/uploads/thumbnails/{S}/{O}",
            "https://cdn.example.com/thumbnails/{S}/not-a-uuid",
            "https://cdn.example.com/thumbnails/{S}/../{O}",
            "https://cdn.example.com/thumbnails/{S}/{O}/extra",
            "https://cdn.example.com/thumbnails/{S}/{O}.jpg",
            "https://cdn.example.com/thumbnails/%33{S}/{O}",
            "https://cdn.example.com/thumbnails/{S}/{O} ",
            "cdn.example.com/thumbnails/{S}/{O}",
            "https://cdn.example.com/thumb nails/{S}/{O}",
            ""
    })
    void rejects(String template) {
        assertRejected(rule, template.replace("{S}", SELLER.toString()).replace("{O}", OBJ), SELLER);
    }

    @Test
    void rejectsOtherSellersThumbnail() {
        assertRejected(rule, OK, OTHER);
    }

    @Test
    void rejectsNull() {
        assertRejected(rule, null, SELLER);
    }

    @Test
    void publicUrlOfPassesVerify() {
        ThumbnailKey key = new ThumbnailKey(SELLER, UUID.fromString(OBJ));

        String url = rule.publicUrlOf(key);

        org.assertj.core.api.Assertions.assertThat(url).isEqualTo(OK);
        assertThatCode(() -> rule.verify(url, SELLER)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdn.example.com", "https://", "not a url", "https://cdn.example.com?x=1"})
    void rejectsBadConfiguration(String base) {
        assertThatThrownBy(() -> ThumbnailUrlRule.of(base)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejected(ThumbnailUrlRule rule, String url, UUID seller) {
        assertThatThrownBy(() -> rule.verify(url, seller))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((InvalidLiveRoomException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_THUMBNAIL_URL);
    }
}
