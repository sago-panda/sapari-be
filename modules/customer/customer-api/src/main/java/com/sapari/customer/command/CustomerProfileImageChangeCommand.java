package com.sapari.customer.command;

/**
 * 구매자 프로필 이미지 변경 요청이다.
 * accessToken으로 본인 여부를 확정하고, 파일 메타데이터는 user-core 검증 경계로 전달한다.
 */
public record CustomerProfileImageChangeCommand(
        String accessToken,
        String originalFilename,
        String contentType,
        byte[] content
) {
    /** accessToken 이 로그·디버그 출력에 남지 않게 하고, 바이트 배열 대신 크기만 보인다. */
    @Override
    public String toString() {
        return "CustomerProfileImageChangeCommand[accessToken=REDACTED, contentType=" + contentType
                + ", size=" + (content == null ? 0 : content.length) + "]";
    }
}
