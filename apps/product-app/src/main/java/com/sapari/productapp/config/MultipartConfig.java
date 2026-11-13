package com.sapari.productapp.config;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.productapp.bulk.BulkProductUploadProcessor;

/**
 * 서블릿 multipart 한도. 기본값(파일 1MB)은 대량 등록 엑셀 한도(5MB)보다 작아 코드로 고정한다 — application yaml은 저장소에 없으므로
 * 배포 환경마다 설정이 달라지지 않게 한다. 이 빈이 있으면 Spring Boot의 multipart 자동 설정은 물러난다.
 */
@Configuration
public class MultipartConfig {

    /** 요청 전체 한도 — 파일 한도에 multipart 경계·헤더 여유를 더한다. */
    private static final long MAX_REQUEST_BYTES = BulkProductUploadProcessor.MAX_FILE_BYTES + 1024 * 1024;

    /** 이 크기를 넘는 파트는 메모리 대신 임시 파일에 둔다. */
    private static final int FILE_SIZE_THRESHOLD_BYTES = 1024 * 1024;

    /**
     * 파일 5MB · 요청 6MB 한도의 multipart 설정. 한도를 넘으면 컨트롤러에 닿기 전에 거절된다.
     *
     * @return multipart 설정
     */
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement(
                "", BulkProductUploadProcessor.MAX_FILE_BYTES, MAX_REQUEST_BYTES, FILE_SIZE_THRESHOLD_BYTES);
    }
}
