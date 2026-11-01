package com.sapari.streamingapp.config;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 룸 토큰 검증용 live 공개키 빈.
 *
 * <p>운영에서는 {@code chat.room-token.public-key}(live RSA 공개키, Base64 X.509 SubjectPublicKeyInfo)를
 * 설정한다. 발급은 live(개인키)만, 이쪽은 검증만 한다 — 대칭 공유키였다면 이 프로세스가 {@code owner=true}
 * 토큰을 위조할 수 있고 그건 PII 게이트 붕괴다.
 */
@Configuration
public class RoomTokenConfig {

    /**
     * 공개키가 없으면 <b>부팅을 실패시킨다</b>.
     *
     * <p>한때 여기서 임시 키를 만들어 뜨게 했다. 근거는 "그 키로는 실제 토큰을 검증할 수 없어 모든
     * 입장이 거부되므로 조용한 우회가 아니라 시끄러운 실패"였는데, <b>시끄러운 쪽이 아니었다.</b>
     * 남는 신호가 부팅 로그의 경고 한 줄뿐이라 배포 파이프라인에서는 성공으로 보인다 — 프로세스가
     * 떠 있는 것 말고는 아무 신호가 없다(이 앱에는 actuator/health 도 없다). 프로세스는 살아 있고 모든 사용자의 입장이 실패하는 상태가 그대로 굴러간다 — 그건
     * 부분 저하가 아니라 기능 전면 중단이다.
     *
     * <p>그 선택이 사는 자리가 하나 있었다: 키를 받기 전에도 앱을 띄워 볼 수 있다는 것. 그건 설정을
     * 비워 두는 것으로 얻을 값이 아니다 — 아무 RSA 공개키나 넣으면 같은 상태가 되고, 그때는 그것이
     * 의도라는 것이 설정에 남는다.
     *
     * <p>이 가드가 지키는 것은 "키가 맞다"가 아니라 "키를 넣기로 결정했다"다. 틀린 키는 입장 실패로
     * 드러나지만 <b>없는 키는 아무것도 드러내지 않는다</b>.
     */
    @Bean
    public PublicKey roomTokenPublicKey(
            @Value("${chat.room-token.public-key:}") String base64Spki) throws Exception {

        if (base64Spki == null || base64Spki.isBlank()) {
            throw new IllegalStateException(
                    "chat.room-token.public-key 가 없다 — 부팅을 중단한다. 이 값이 없으면 룸 토큰을 "
                            + "검증할 수 없어 모든 입장이 실패하므로, 뜨는 것보다 안 뜨는 쪽이 낫다. "
                            + "live 가 발급에 쓰는 RSA 키쌍의 공개키를 Base64 X.509 "
                            + "SubjectPublicKeyInfo 로 주입할 것(PEM 헤더 없이 본문만, 개행 허용).");
        }

        byte[] der;
        try {
            // MIME 디코더다 — <b>개행을 허용</b>한다. 설정 템플릿이 지시하는 값이 "PEM 헤더 없이
            // 본문만"이고 PEM 본문은 64열로 줄바꿈된 문자열인데, basic 디코더는 그 개행을 거부해
            // <b>유효한 base64 를 "Base64 가 아니다"로 오진한다</b>(실측: Illegal base64 character a).
            // 이 가드의 목적이 친절한 실패인데 가장 흔한 입력 모양에서 바로 그 목적이 깨졌다.
            //
            // 헤더까지 붙여 넣은 값은 MIME 디코더에서 쓰레기 바이트로 디코드돼 아래 두 번째 분기
            // ("RSA X.509 공개키가 아니다")로 떨어진다 — 그쪽이 더 정확한 진단이다(실측).
            der = Base64.getMimeDecoder().decode(base64Spki.trim());
        } catch (IllegalArgumentException e) {
            // 친절한 실패가 이 가드의 목적이다 — 여기서 디코더 예외로 죽으면 원인이 "설정 값이 Base64가
            // 아니다"라는 게 로그에서 바로 읽히지 않는다. 값 자체는 싣지 않는다(공개키라 비밀은 아니지만
            // 설정 값을 로그에 흘리는 선례를 만들지 않는다).
            //
            // 여기 오는 것은 <b>패딩·길이가 깨진</b> 입력이다. 비-base64 문자는 MIME 디코더가 무시하므로
            // 그런 값은 아래 키 분기로 떨어진다(실측).
            throw new IllegalStateException(
                    "chat.room-token.public-key 가 Base64 가 아니다 — 길이=" + base64Spki.trim().length(), e);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "chat.room-token.public-key 가 RSA X.509 공개키가 아니다 — live 가 발급에 쓰는 키쌍의 "
                            + "공개키인지 확인할 것(개인키나 인증서를 넣으면 여기서 걸린다).", e);
        }
    }
}
