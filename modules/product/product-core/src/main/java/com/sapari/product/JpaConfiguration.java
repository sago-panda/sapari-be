package com.sapari.product;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.sapari.product.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.sapari.product.infrastructure.persistence.entity")
public class JpaConfiguration {

}
