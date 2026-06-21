package com.sapari.product.support;

import com.navercorp.fixturemonkey.ArbitraryBuilder;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.model.product.ProductStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;

/**
 * Product 애그리거트 테스트 픽스처 — FixtureMonkey로 생성한다.
 *
 * <p>도메인이 불변 record라 {@link ConstructorPropertiesArbitraryIntrospector}(카논 생성자)로 생성하고,
 * {@code defaultNotNull}로 NOT NULL 컬럼을 보장한다. 그 위에 "영속 불가/불변식 위반" 조합만 빌더에서 제약한다:
 * <ul>
 *   <li>문자열은 alpha로 한정 — Postgres text/varchar가 거부하는 NUL 문자 회피 + 길이 상한 준수
 *   <li>avgRating은 numeric(3,1) 위반을 피하려 기본 null(필요 시 명시 세팅)
 *   <li>jsonb(metadata·optionConfig)는 null 또는 유효 JSON만 — 무작위 문자열은 jsonb 파싱 실패
 *   <li>옵션 타입/값 이름은 인덱스로 유니크하게 — partial unique(product_id,name)/(option_type_id,value) 충돌 회피
 *   <li>상품 소유 이미지는 productId XOR optionValueId, role은 SWATCH 제외(SWATCH→option_value_id CHECK)
 * </ul>
 * 핀(pin)하지 않은 스칼라(name·description·basePrice·집계 카운트·shippingPolicyId 등)는 FixtureMonkey가
 * 무작위로 채우므로, 테스트는 그 값을 그대로 다시 읽혀(round-trip fidelity) 영속 충실도를 검증한다.
 */
public final class ProductFixtures {

    private ProductFixtures() {
    }

    public static final UUID SELLER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID SELLER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    public static final Long CATEGORY_1 = 1001L;
    public static final Long CATEGORY_2 = 1002L;

    public static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    /**
     * 영속 가능한 Product 빌더(자식 없음·신규 INSERT 경로). 테스트가 추가로 커스터마이즈해 sample()한다.
     */
    public static ArbitraryBuilder<Product> aProduct(UUID sellerId, Long categoryId) {
        return FM.giveMeBuilder(Product.class)
                .setNull("id")
                .set("sellerId", sellerId)
                .set("categoryId", categoryId)
                .set("name", Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(40))
                .set("description", Arbitraries.strings()
                        .alpha()
                        .ofMinLength(0)
                        .ofMaxLength(200))
                .set("rejectionReason", Arbitraries.strings()
                        .alpha()
                        .ofMinLength(0)
                        .ofMaxLength(50))
                .set("basePrice", Arbitraries.integers()
                        .between(0, 10_000_000))
                .set("additionalShippingFee", Arbitraries.integers()
                        .between(0, 50_000))
                .set("minPrice", Arbitraries.integers()
                        .between(0, 10_000_000))
                .set("purchaseCount", Arbitraries.integers()
                        .between(0, 1_000_000))
                .set("reviewCount", Arbitraries.integers()
                        .between(0, 1_000_000))
                .set("viewCount", Arbitraries.integers()
                        .between(0, 1_000_000))
                .set("wishlistCount", Arbitraries.integers()
                        .between(0, 1_000_000))
                .setNull("avgRating")        // numeric(3,1) 위반 회피
                .setNull("deletedAt")
                .setNull("searchIndexedAt")  // timestamptz는 ns→µs 절삭으로 왕복 비교가 흔들려 기본 제외
                .setNull("optionConfig")
                .set("tags", List.of())
                .set("images", List.of())
                .set("optionTypes", List.of());
    }

    /**
     * 자식 없는 최소 상품.
     */
    public static Product minimal(UUID sellerId, Long categoryId) {
        return aProduct(sellerId, categoryId).sample();
    }

    /**
     * 태그·옵션 타입/값(FixtureMonkey 생성)·옵션 config(유효 jsonb)·avgRating을 채운 풀 상품.
     */
    public static Product full(UUID sellerId, Long categoryId) {
        return aProduct(sellerId, categoryId)
                .set("avgRating", new BigDecimal("4.5"))
                .size("tags", 2)
                .set("tags[*]", Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(20))
                .set("optionTypes", optionTypes(2))
                .set("optionConfig", ProductOptionConfigModel.of(
                        "[{\"amount\":1000,\"valueIds\":[\"v1\",\"v2\"]}]", null))
                .sample();
    }

    /**
     * FixtureMonkey로 옵션 타입 {@code count}개 생성. 타입명·값명은 인덱스로 유니크, 나머지는 무작위.
     */
    public static List<ProductOptionTypeModel> optionTypes(int count) {
        return IntStream.range(0, count)
                .mapToObj(ProductFixtures::optionType)
                .toList();
    }

    private static ProductOptionTypeModel optionType(int typeIndex) {
        int valueCount = Arbitraries.integers()
                .between(1, 3)
                .sample();
        List<ProductOptionValueModel> values = IntStream.range(0, valueCount)
                .mapToObj(j -> FM.giveMeBuilder(ProductOptionValueModel.class)
                        .setNull("id")
                        .setNull("attributePresetId")
                        .setNull("metadata")                       // jsonb: 무작위 문자열 금지
                        .set("value", "t" + typeIndex + "v" + j)   // (option_type_id, value) 유니크
                        .set("priceDelta", Arbitraries.integers()
                                .between(0, 50_000))
                        .sample())
                .toList();
        return FM.giveMeBuilder(ProductOptionTypeModel.class)
                .setNull("id")
                .setNull("attributeGroupId")
                .set("name", "type" + typeIndex)               // (product_id, name) 유니크
                .set("values", values)
                .sample();
    }

    /**
     * 상품 소유 이미지 {@code count}개를 FixtureMonkey로 생성(productId 세팅, optionValueId=null, role≠SWATCH).
     */
    public static List<ProductImageRef> productImages(UUID productId, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> FM.giveMeBuilder(ProductImageRef.class)
                        .setNull("id")
                        .set("productId", productId)
                        .setNull("optionValueId")
                        .set("role", Arbitraries.of(ImageRole.GALLERY, ImageRole.DETAIL, ImageRole.BODY))
                        .set("imageKey", Arbitraries.strings()
                                .alpha()
                                .ofMinLength(1)
                                .ofMaxLength(100))
                        .sample())
                .toList();
    }
}
