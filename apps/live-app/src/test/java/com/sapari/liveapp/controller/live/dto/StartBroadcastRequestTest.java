package com.sapari.liveapp.controller.live.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.navercorp.fixturemonkey.jakarta.validation.plugin.JakartaValidationPlugin;
import com.sapari.apiapp.controller.live.dto.StartBroadcastRequest;
import com.sapari.apiapp.controller.live.dto.StartBroadcastRequest.ProductRequest;

class StartBroadcastRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static FixtureMonkey fixtureMonkey;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .plugin(new JakartaValidationPlugin())
                .build();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @RepeatedTest(10)
    @DisplayName("FixtureMonkey가 생성한 요청은 항상 제약을 만족한다 (랜덤 데이터로도 위반 없음)")
    void generatedRequest_alwaysValid() {
        StartBroadcastRequest request = fixtureMonkey.giveMeOne(StartBroadcastRequest.class);

        Set<ConstraintViolation<StartBroadcastRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("products가 null이면 @NotEmpty 위반이 발생한다 (HTTP 경계에서 @Valid가 거름)")
    void products_null_violatesNotEmpty() {
        StartBroadcastRequest request = new StartBroadcastRequest(null);

        Set<ConstraintViolation<StartBroadcastRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("products"));
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("라이브 상품은 최소 1개 이상이어야 합니다.");
    }

    @Test
    @DisplayName("products가 빈 리스트여도 @NotEmpty 위반이 발생한다")
    void products_empty_violatesNotEmpty() {
        StartBroadcastRequest request = new StartBroadcastRequest(List.of());

        Set<ConstraintViolation<StartBroadcastRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("products"));
    }

    @Test
    @DisplayName("리스트 안에 null 원소가 있으면 원소 @NotNull 위반이 발생한다")
    void products_withNullElement_violatesNotNull() {
        ProductRequest valid = fixtureMonkey.giveMeOne(ProductRequest.class);
        List<ProductRequest> withNullElement = new ArrayList<>();
        withNullElement.add(valid);
        withNullElement.add(null);

        StartBroadcastRequest request = new StartBroadcastRequest(withNullElement);

        Set<ConstraintViolation<StartBroadcastRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().contains("products"));
    }

    @Test
    @DisplayName("검증을 우회해 products=null로 직접 생성 후 toProductEntries() 호출 시 NPE가 발생한다")
    void toProductEntries_whenNull_throwsNpe() {
        StartBroadcastRequest request = new StartBroadcastRequest(null);

        assertThatThrownBy(request::toProductEntries)
                .isInstanceOf(NullPointerException.class);
    }
}
