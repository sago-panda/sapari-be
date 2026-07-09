package com.sapari.user.application.port;

/**
 * DB에 저장된 내부 profileImageKey를 API 응답용 profileImageUrl로 변환한다.
 * 도메인 모델은 공개 URL을 들고 있지 않고, 구현체가 조회 시점의 공개 URL 정책을 적용한다.
 */
public interface ProfileImageUrlResolver {

    /**
     * 내부 object key를 클라이언트가 접근 가능한 공개 URL로 변환한다.
     * key가 없으면 기본 이미지 정책은 호출 계층에 맡기고 null을 반환한다.
     */
    String resolve(String profileImageKey);
}
