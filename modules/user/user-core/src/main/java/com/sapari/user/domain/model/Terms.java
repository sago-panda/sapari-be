package com.sapari.user.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

import com.sapari.user.model.TermsType;

/**
 * 가입 시 사용자가 동의한 약관 버전을 식별하기 위한 불변 기준 데이터다.
 * 이미 증적에 사용된 약관은 삭제/수정하지 않고 새 version row로 변경해 당시 버전을 복원 가능하게 한다.
 * 약관 전문은 DB에 저장하지 않고 contentUrl로 참조해 증적 row가 당시 전문 위치를 가리키게 한다.
 * required는 표시/메타데이터 성격이며, 현재 가입 필수 동의 검증은 TermsType.PRIVACY 정책으로 강제한다.
 */
public record Terms(
        UUID termsId,
        TermsType type,
        String version,
        String title,
        boolean required,
        String contentUrl,
        String contentFormat,
        Instant effectiveFrom,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {

    public static Terms create(
            TermsType type,
            String version,
            String title,
            boolean required,
            String contentUrl,
            String contentFormat,
            Instant effectiveFrom
    ) {
        return of(
                null,
                type,
                version,
                title,
                required,
                contentUrl,
                contentFormat,
                effectiveFrom,
                true,
                null,
                null
        );
    }

    public static Terms of(
            UUID termsId,
            TermsType type,
            String version,
            String title,
            boolean required,
            String contentUrl,
            String contentFormat,
            Instant effectiveFrom,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {
        Assert.notNull(type, "type은 필수입니다.");
        Assert.hasText(version, "version은 필수입니다.");
        Assert.hasText(title, "title은 필수입니다.");
        Assert.hasText(contentUrl, "contentUrl은 필수입니다.");
        Assert.hasText(contentFormat, "contentFormat은 필수입니다.");
        Assert.notNull(effectiveFrom, "effectiveFrom은 필수입니다.");

        return new Terms(
                termsId,
                type,
                version,
                title,
                required,
                contentUrl,
                contentFormat,
                effectiveFrom,
                active,
                createdAt,
                updatedAt
        );
    }
}
