package com.sapari.live.infrastructure.config;

import io.livekit.server.EgressServiceClient;
import io.livekit.server.IngressServiceClient;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import io.livekit.server.okhttp.OkHttpFactory;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveKitProperties.class)
public class LiveKitConfig {

    /**
     * LiveKit HTTP 클라이언트. 세 서비스 클라이언트가 공유한다(팩토리 내부가 Lazy 라 인스턴스는 하나).
     *
     * <p>{@code callTimeout} 은 잠금·DB 커넥션 보유 시간의 상한이다 — 미디어 호출이 트랜잭션 안에서
     * 일어나므로(modules/live/AGENTS.md "Media (SFU / HLS)" 절), 이게 없으면 같은 방에 대한
     * 다른 전이가 무한정 대기한다. 15s 는 소켓 기본값(10s)보다 크게 잡아 읽는 중에 잘리지 않게 한 값.
     *
     * <p>logging 은 반드시 false — true 면 요청 서명 JWT 와 createIngress 응답의 streamKey 가 로그에 찍힌다.
     */
    @Bean
    public OkHttpFactory liveKitOkHttpFactory() {
        return new OkHttpFactory(false, builder -> builder.callTimeout(Duration.ofSeconds(15)));
    }

    @Bean
    public RoomServiceClient roomServiceClient(LiveKitProperties liveKitProperties, OkHttpFactory liveKitOkHttpFactory){
        return RoomServiceClient.createClient(
                liveKitProperties.host(),
                liveKitProperties.apiKey(),
                liveKitProperties.apiSecret(),
                liveKitOkHttpFactory
        );
    }

    @Bean
    public EgressServiceClient egressServiceClient(LiveKitProperties liveKitProperties, OkHttpFactory liveKitOkHttpFactory){
        return EgressServiceClient.createClient(
                liveKitProperties.host(),
                liveKitProperties.apiKey(),
                liveKitProperties.apiSecret(),
                liveKitOkHttpFactory
        );
    }

    @Bean
    public IngressServiceClient ingressServiceClient(LiveKitProperties liveKitProperties, OkHttpFactory liveKitOkHttpFactory){
        return IngressServiceClient.createClient(
                liveKitProperties.host(),
                liveKitProperties.apiKey(),
                liveKitProperties.apiSecret(),
                liveKitOkHttpFactory
        );
    }

    @Bean
    public WebhookReceiver webhookReceiver(LiveKitProperties liveKitProperties){
        return new WebhookReceiver(liveKitProperties.apiKey(), liveKitProperties.apiSecret());
    }
}
