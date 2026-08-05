package com.sapari.apiapp.controller.support.multipart;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;

/**
 * 프로필 이미지 multipart 파일을 use case 입력값으로 변환하는 컨트롤러 계층 helper다.
 * 파일 형식·크기·이미지 decode 검증은 user-core의 프로필 이미지 검증 경계에서 수행한다.
 */
public final class ProfileImageMultipartFileReader {

    private ProfileImageMultipartFileReader() {
    }

    /** MultipartFile을 도메인 비의존 데이터로 읽고 I/O 실패는 프로필 이미지 오류로 변환한다. */
    public static ProfileImageFile read(MultipartFile file) {
        try {
            return new ProfileImageFile(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes()
            );
        } catch (IOException e) {
            throw new UserException(UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT, e);
        }
    }

    /** HTTP multipart 경계를 벗어나 전달할 파일명·media type·원본 바이트 묶음이다. */
    public record ProfileImageFile(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
    }
}
