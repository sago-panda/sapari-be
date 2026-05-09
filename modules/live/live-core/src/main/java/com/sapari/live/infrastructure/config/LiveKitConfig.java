package com.sapari.live.infrastructure.config;

import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomServiceClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LiveKitProperties.class)
public class LiveKitConfig {

    @Bean
    public RoomServiceClient roomServiceClient(LiveKitProperties liveKitProperties){
        return RoomServiceClient.createClient(liveKitProperties.host(), liveKitProperties.apiKey(), liveKitProperties.apiSecret());
    }

    @Bean
    public EgressServiceClient egressServiceClient(LiveKitProperties liveKitProperties){
        return EgressServiceClient.createClient(liveKitProperties.host(), liveKitProperties.apiKey(), liveKitProperties.apiSecret());
    }
}
