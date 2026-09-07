package com.sapari.customer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.user.command.ProfileImagePrepareCommand;
import com.sapari.user.view.PreparedProfileImage;

class ProfileImageImmutabilityTest {

    /** 생성 인자와 accessor를 통해 검증 대상 바이트가 사후 변경되는 회귀를 막는다. */
    @ParameterizedTest
    @MethodSource("imageFactories")
    void isolatesBothInputAndOutput(Function<byte[], Supplier<byte[]>> factory) {
        byte[] source = {1, 2, 3};
        Supplier<byte[]> content = factory.apply(source);
        source[0] = 9;
        content.get()[1] = 9;
        assertThat(content.get()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    /** 필수 이미지의 빈 내용과 저장 형식 불일치를 생성 경계에서 거부한다. */
    @Test
    void rejectsInvalidPreparedMetadata() {
        assertThatThrownBy(() -> new PreparedProfileImage("png", "image/jpeg", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedProfileImage("png", "image/png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedProfileImage("png", "image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 선택 이미지가 없는 기존 가입 계약은 유지한다. */
    @Test
    void preservesAbsentOptionalImage() {
        assertThat(signup(null).profileImageContent()).isNull();
        assertThat(signup(null).hasUploadedProfileImage()).isFalse();
        assertThat(new ProfileImagePrepareCommand(null, null, null).content()).isNull();
    }

    /** 네 전달 타입의 실제 accessor를 동일한 변경 시나리오로 검증한다. */
    static Stream<Function<byte[], Supplier<byte[]>>> imageFactories() {
        return Stream.of(
                bytes -> new PreparedProfileImage("png", "image/png", bytes)::content,
                bytes -> new ProfileImagePrepareCommand("image.png", "image/png", bytes)::content,
                bytes -> new SocialProfileImageDownloadResult("png", "image/png", bytes)::content,
                bytes -> signup(bytes)::profileImageContent);
    }

    /** 선택 이미지 필드만 사용하는 가입 입력을 만든다. */
    private static SocialSignupCommand signup(byte[] bytes) {
        return new SocialSignupCommand(null, null, null, null, null, null,
                false, "image.png", "image/png", bytes, true, false);
    }
}
