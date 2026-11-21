package com.sapari.apiapp.controller.support.multipart;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("프로필 이미지 multipart 파일 reader 테스트")
class ProfileImageMultipartFileReaderTest {

    @Test
    @DisplayName("multipart 임시파일을 읽지 못하면 서버 I/O 오류로 전달한다")
    void propagatesMultipartReadFailureAsServerError() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        IOException cause = new IOException("temporary file read failed");
        when(file.getBytes()).thenThrow(cause);

        assertThatThrownBy(() -> ProfileImageMultipartFileReader.read(file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCause(cause);
    }
}
