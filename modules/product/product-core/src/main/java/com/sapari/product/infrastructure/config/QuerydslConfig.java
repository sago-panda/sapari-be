package com.sapari.product.infrastructure.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 설정. 영속 어댑터가 동적·프로젝션 쿼리에 쓰는 {@link JPAQueryFactory}를 빈으로 등록한다.
 */
@Configuration
public class QuerydslConfig {

    /**
     * 주입받은 {@link EntityManager}로 {@link JPAQueryFactory}를 생성한다.
     */
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
