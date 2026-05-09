package com.sapari.apiapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.sapari")
@EnableJpaRepositories(basePackages = "com.sapari.live.infrastructure.persistence")
@EntityScan(basePackages = "com.sapari.live.infrastructure.persistence")
public class ApiAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiAppApplication.class, args);
    }

}
