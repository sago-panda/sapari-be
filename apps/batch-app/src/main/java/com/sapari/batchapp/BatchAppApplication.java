package com.sapari.batchapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.sapari")
@EnableJpaRepositories(basePackages = {
        "com.sapari.seller.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@EntityScan(basePackages = {
        "com.sapari.seller.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@ConfigurationPropertiesScan
public class BatchAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchAppApplication.class, args);
    }
}
