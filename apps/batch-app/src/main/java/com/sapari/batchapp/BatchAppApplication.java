package com.sapari.batchapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.sapari.batchapp",
        "com.sapari.global",
        "com.sapari.seller.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@EnableJpaRepositories(basePackages = {
        "com.sapari.seller.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@EntityScan(basePackages = {
        "com.sapari.seller.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@ConfigurationPropertiesScan
@EnableScheduling
public class BatchAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchAppApplication.class, args);
    }
}
