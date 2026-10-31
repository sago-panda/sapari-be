package com.sapari.streamingapp.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 커맨드 상한.
 *
 * <p><b>왜 기본값에 맡기지 않는가.</b> Lettuce 의 기본 커맨드 타임아웃은 <b>60초</b>이고
 * ({@code RedisURI.DEFAULT_TIMEOUT = 60l}, lettuce-core 6.8.2 바이트코드 실측) 이 저장소에
 * {@code spring.data.redis.timeout} 이 설정된 곳이 없었다. Redis 를 죽이는 대신 <b>패킷을 떨구면</b>
 * (파티션·과부하) 커맨드가 그 60초까지 pending 이고, 전송 경로가 {@code concatMap} 으로 직렬화돼
 * 있어 그 세션의 채팅이 통째로 그만큼 멈춘다.
 *
 * <p>연산자 상한({@code .timeout()})으로는 그 구간을 덮지 못한다 — {@code ReactiveRedisTemplate} 이
 * 커넥션 획득을 {@code subscribe()} 경로에서 동기로 하고 {@code .timeout()} 은 구독이 반환된 뒤에
 * 무장된다. 블랙홀 리스너로 실측: 따뜻한 커넥션은 210ms 내외로 상한이 듣고, <b>차가운 커넥션은
 * 60,245ms</b> 뒤에 끝난다({@code .timeout(200ms)} 도 {@code .block(1s)} 도 못 끊는다). 그래서
 * 상한은 <b>클라이언트 쪽</b>에 있어야 한다.
 *
 * <p><b>왜 코드에 두는가.</b> {@code application*.yml} 은 추적되지 않으므로 템플릿에만 적으면
 * 복사하지 않은 환경에 적용되지 않는다 — 같은 모양으로 세션 상한이 이미 한 번 샜다. 여기 두면
 * 설정이 없어도 기본값이 적용되고, {@code spring.data.redis.timeout} 으로 덮을 수 있다(그 값을
 * 직접 읽으므로 Boot 가 먼저 적용한 값을 이 커스터마이저가 되돌리지 않는다).
 *
 * <p><b>정책 결과를 분명히 적는다.</b> 이 값은 chat 의 <b>모든</b> Redis 호출에 걸리고, 그중 강퇴·밴·
 * 종료 마커 조회는 실패 시 fail-open 이다. 즉 상한을 넘기면 "느린 Redis = 그 판정이 통과" 가 된다.
 * 그건 정본({@code modules/chat/AGENTS.md})이 이미 <b>의도로</b> 적어 둔 정책이고, 60초 쪽이
 * 그 정책을 집행하지 못한 채 가용성만 잃던 이상값이었다. 레이트리밋은 그 경로에서 로컬 창으로
 * 강등되므로 상한이 사라지지 않는다.
 *
 * <p><b>장수 구독은 영향을 받지 않는다</b>(실측). 브로드캐스터의 {@code listenToPattern} 은 구독
 * 확립 응답에만 이 상한이 걸리고 이후 메시지 전달에는 걸리지 않는다 — 1초 상한에서 3초를 조용히
 * 보낸 뒤 발행한 메시지가 정상 수신되고 구독 에러가 0 이다. 그 사실을
 * {@code RedisSubscriptionSurvivesCommandTimeoutTest} 가 고정한다.
 *
 * <p>1초를 고른 근거: 정상 왕복은 ms 아래이고, 가장 큰 호출(시청자 수의 {@code HVALS} 가 방 해시
 * 전체를 당겨온다)도 설계 상한인 12,000 세션에서 ms 자릿수다. 측정된 값이 아니라 두 자릿수 이상의
 * 여유를 둔 값이다 — 레이트리밋은 자기 경로에 200ms 를 따로 걸고 있어 이 값이 그쪽을 느슨하게
 * 만들지 않는다.
 */
@Configuration
public class RedisTimeoutConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisCommandTimeout(
            @Value("${spring.data.redis.timeout:1s}") Duration commandTimeout) {
        return builder -> builder.commandTimeout(commandTimeout);
    }
}
