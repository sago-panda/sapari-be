package com.sapari.liveapp.controller.live.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.view.LiveStreamKind;

@DisplayName("CreateRoomRequest — 길이 한도는 DB 컬럼과 같고, description 은 선택")
class CreateRoomRequestTest {

    private static final String THUMB = "https://cdn.example.com/thumbnails/s/o";
    private static final Instant AT = Instant.parse("2026-11-09T00:00:00Z");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("한도값은 모두 통과한다")
    void limitsPass() {
        assertThat(validator.validate(new CreateRoomRequest(
                "가".repeat(100), "가".repeat(500), url(500), AT, LiveStreamKind.WEBRTC))).isEmpty();
    }

    @Test
    @DisplayName("한도+1 은 각 필드에서 위반")
    void limitPlusOneFails() {
        assertViolationOn(new CreateRoomRequest("가".repeat(101), null, THUMB, AT, LiveStreamKind.WEBRTC), "title");
        assertViolationOn(new CreateRoomRequest("제목", "가".repeat(501), THUMB, AT, LiveStreamKind.WEBRTC), "description");
        assertViolationOn(new CreateRoomRequest("제목", null, url(501), AT, LiveStreamKind.WEBRTC), "thumbnailUrl");
    }

    @Test
    @DisplayName("description 은 null·빈 문자열·공백이어도 통과한다")
    void descriptionOptional() {
        assertThat(validator.validate(new CreateRoomRequest("제목", null, THUMB, AT, LiveStreamKind.WEBRTC))).isEmpty();
        assertThat(validator.validate(new CreateRoomRequest("제목", "", THUMB, AT, LiveStreamKind.WEBRTC))).isEmpty();
        assertThat(validator.validate(new CreateRoomRequest("제목", "   ", THUMB, AT, LiveStreamKind.WEBRTC))).isEmpty();
    }

    @Test
    @DisplayName("필수 필드의 공백·null 은 위반")
    void requiredFields() {
        assertViolationOn(new CreateRoomRequest(" ", null, THUMB, AT, LiveStreamKind.WEBRTC), "title");
        assertViolationOn(new CreateRoomRequest("제목", null, " ", AT, LiveStreamKind.WEBRTC), "thumbnailUrl");
        assertViolationOn(new CreateRoomRequest("제목", null, THUMB, null, LiveStreamKind.WEBRTC), "scheduledAt");
        assertViolationOn(new CreateRoomRequest("제목", null, THUMB, AT, null), "streamType");
    }

    @Test
    @DisplayName("이모지는 UTF-16 두 단위로 세어 DB 보다 엄격하다 — 이모지 100개 제목은 위반(spec Edge Case 허용)")
    void emojiCountsAsTwoUnits() {
        assertViolationOn(new CreateRoomRequest("😀".repeat(100), null, THUMB, AT, LiveStreamKind.WEBRTC), "title");
        assertThat(validator.validate(new CreateRoomRequest("😀".repeat(50), null, THUMB, AT, LiveStreamKind.WEBRTC))).isEmpty();
    }

    private static String url(int length) {
        String prefix = "https://cdn.example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }

    private static void assertViolationOn(CreateRoomRequest request, String field) {
        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(request);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly(field);
    }
}
