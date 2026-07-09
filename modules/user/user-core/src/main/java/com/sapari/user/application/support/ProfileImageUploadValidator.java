package com.sapari.user.application.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.global.validator.ImageFileValidator;
import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;

/**
 * 공통 이미지 검증 결과를 프로필 이미지 저장 command와 회원 도메인 에러 코드로 연결한다.
 */
@Component
public class ProfileImageUploadValidator {

    /**
     * 프로필 이미지 원본을 검증·재인코딩한 뒤 저장 계층에 넘길 command로 변환한다.
     */
    public ProfileImageStoreCommand validate(
            UUID userId,
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        try {
            ImageFileValidator.ValidatedImageFile image =
                    ImageFileValidator.validate(originalFilename, contentType, content);

            return new ProfileImageStoreCommand(
                    userId,
                    image.normalizedExtension(),
                    image.contentType(),
                    image.content()
            );
        } catch (ImageFileValidator.ImageFileValidationException e) {
            throw toUserException(e);
        }
    }

    /**
     * common/global의 파일 검증 실패 이유를 회원 프로필 이미지 API의 응답 코드로 변환한다.
     * common validator가 UserException을 직접 알지 않도록 하는 도메인 경계 매핑이다.
     */
    private UserException toUserException(ImageFileValidator.ImageFileValidationException e) {
        return switch (e.reason()) {
            case REQUIRED -> new UserException(UserErrorCode.PROFILE_IMAGE_REQUIRED, e);
            case TOO_LARGE -> new UserException(UserErrorCode.PROFILE_IMAGE_TOO_LARGE, e);
            case UNSUPPORTED_TYPE -> new UserException(UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE, e);
            case INVALID_CONTENT -> new UserException(UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT, e);
        };
    }
}
