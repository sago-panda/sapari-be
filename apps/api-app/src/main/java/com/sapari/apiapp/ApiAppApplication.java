package com.sapari.apiapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.sapari")
@EnableJpaRepositories(basePackages = {
        "com.sapari.live.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@EntityScan(basePackages = {
        "com.sapari.live.infrastructure.persistence",
        "com.sapari.user.infrastructure.persistence"
})
@ConfigurationPropertiesScan
public class ApiAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiAppApplication.class, args);
    }

}
