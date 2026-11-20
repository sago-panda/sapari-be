package com.sapari.apiapp.controller.support.multipart;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.web.multipart.MultipartFile;

/**
 * 프로필 이미지 multipart 파일을 use case 입력값으로 변환하는 컨트롤러 계층 helper다.
 * 파일 형식·크기·이미지 decode 검증은 user-core의 프로필 이미지 검증 경계에서 수행한다.
 */
public final class ProfileImageMultipartFileReader {

    private ProfileImageMultipartFileReader() {
    }

    /** MultipartFile을 도메인 비의존 데이터로 읽고 서버 I/O 실패는 내부 오류로 전달한다. */
    public static ProfileImageFile read(MultipartFile file) {
        try {
            return new ProfileImageFile(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes()
            );
        } catch (IOException e) {
            throw new UncheckedIOException("프로필 이미지 multipart 파일을 읽을 수 없습니다.", e);
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
