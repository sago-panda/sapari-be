package com.sapari.live.application.port;

/**
 * HLS master.m3u8(멀티 화질 목차)를 객체 스토리지에 게시하는 아웃바운드 포트.
 *
 * <p>구현체(예: NCP Object Storage 어댑터)는 인프라 계층에 둔다. 스토리지 링크가 정해지기 전에는
 * 이 빈이 등록되지 않으며, 그 경우 {@code startHlsEgress}는 master 업로드를 건너뛰고 기본 화질 변형을
 * 직접 서빙한다(ABR만 비활성, 방송은 정상). 어댑터 빈이 등록되는 순간 자동으로 master 서빙으로 전환된다.
 */
public interface MasterPlaylistPublisher {

    /**
     * master.m3u8 콘텐츠를 주어진 오브젝트 키로 업로드한다(세그먼트·variant와 같은 버킷·경로 기준).
     *
     * @param objectKey 버킷 내 키. 예: {@code live/{roomId}/master.m3u8}
     * @param content   master.m3u8 텍스트
     */
    void publish(String objectKey, String content);
}
