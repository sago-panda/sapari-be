package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.exception.InvalidProductStateException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Product 애그리거트 전이")
class ProductTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Long CATEGORY_ID = 1001L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");

    private static Product pendingProduct() {
        return Product.create(
                SELLER_ID, CATEGORY_ID, "베이직 티셔츠", "설명", 19_900,
                null, 0, ProductOptionModel.COMBINATION, NOW);
    }

    private static Product onSaleProduct() {
        return pendingProduct().approve(NOW);
    }

    @Nested
    @DisplayName("suspend (판매 중지)")
    class Suspend {

        @Test
        @DisplayName("ON_SALE면 SUSPENDED로 전이하고 updatedAt만 갱신하며 나머지 식별·내용 필드는 보존한다")
        void suspendsFromOnSale() {
            Product onSale = onSaleProduct();

            Product suspended = onSale.suspend(LATER);

            assertThat(suspended.status()).isEqualTo(ProductStatus.SUSPENDED);
            assertThat(suspended.updatedAt()).isEqualTo(LATER);
            // 전이는 상태·updatedAt만 바꾼다 — 식별/내용/생성시각은 그대로
            assertThat(suspended.sellerId()).isEqualTo(onSale.sellerId());
            assertThat(suspended.categoryId()).isEqualTo(onSale.categoryId());
            assertThat(suspended.name()).isEqualTo(onSale.name());
            assertThat(suspended.basePrice()).isEqualTo(onSale.basePrice());
            assertThat(suspended.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("ON_SALE이 아니면 InvalidProductStateException (PENDING_REVIEW)")
        void rejectsSuspendFromPending() {
            Product pending = pendingProduct();

            assertThatThrownBy(() -> pending.suspend(LATER))
                    .isInstanceOf(InvalidProductStateException.class);
        }

        @Test
        @DisplayName("이미 SUSPENDED면 InvalidProductStateException")
        void rejectsSuspendWhenAlreadySuspended() {
            Product suspended = onSaleProduct().suspend(NOW);

            assertThatThrownBy(() -> suspended.suspend(LATER))
                    .isInstanceOf(InvalidProductStateException.class);
        }

        @Test
        @DisplayName("소프트 삭제된 상품은 ON_SALE 상태여도 중지할 수 없다")
        void rejectsSuspendWhenDeleted() {
            Product deleted = onSaleProduct().softDelete(NOW);

            assertThatThrownBy(() -> deleted.suspend(LATER))
                    .isInstanceOf(InvalidProductStateException.class);
        }
    }

    @Nested
    @DisplayName("resume (판매 재개)")
    class Resume {

        @Test
        @DisplayName("SUSPENDED면 ON_SALE로 전이하고 updatedAt을 갱신한다")
        void resumesFromSuspended() {
            Product resumed = onSaleProduct().suspend(NOW).resume(LATER);

            assertThat(resumed.status()).isEqualTo(ProductStatus.ON_SALE);
            assertThat(resumed.updatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("SUSPENDED가 아니면 InvalidProductStateException (ON_SALE)")
        void rejectsResumeFromOnSale() {
            Product onSale = onSaleProduct();

            assertThatThrownBy(() -> onSale.resume(LATER))
                    .isInstanceOf(InvalidProductStateException.class);
        }
    }

    @Nested
    @DisplayName("updateInfo (정보 수정 → 재검수)")
    class UpdateInfo {

        @Test
        @DisplayName("ON_SALE 상품 정보 수정 시 대상 필드만 갱신되고 PENDING_REVIEW로 전이한다")
        void updatesAndMovesToReview() {
            UUID newShippingPolicy = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
            Product onSale = onSaleProduct();

            Product updated = onSale.updateInfo(2002L, "리뉴얼 티셔츠", "새 설명", newShippingPolicy, 3_000, LATER);

            assertThat(updated.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(updated.categoryId()).isEqualTo(2002L);
            assertThat(updated.name()).isEqualTo("리뉴얼 티셔츠");
            assertThat(updated.description()).isEqualTo("새 설명");
            assertThat(updated.shippingPolicyId()).isEqualTo(newShippingPolicy);
            assertThat(updated.additionalShippingFee()).isEqualTo(3_000);
            assertThat(updated.updatedAt()).isEqualTo(LATER);
            // updateInfo가 건드리지 않는 필드는 보존 (가격·판매자·생성시각)
            assertThat(updated.basePrice()).isEqualTo(onSale.basePrice());
            assertThat(updated.sellerId()).isEqualTo(onSale.sellerId());
            assertThat(updated.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("반려(REJECTED) 상품을 수정·재제출하면 rejectionReason이 비워지고 PENDING_REVIEW가 된다")
        void clearsRejectionReasonOnResubmit() {
            Product rejected = pendingProduct().reject("이미지 부적절", NOW);

            Product resubmitted = rejected.updateInfo(
                    CATEGORY_ID, "수정된 티셔츠", "설명", null, 0, LATER);

            assertThat(resubmitted.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(resubmitted.rejectionReason()).isNull();
        }

        @Test
        @DisplayName("additionalShippingFee가 null이면 0으로 정규화한다")
        void normalizesNullShippingFee() {
            Product updated = onSaleProduct().updateInfo(
                    CATEGORY_ID, "티셔츠", "설명", null, null, LATER);

            assertThat(updated.additionalShippingFee()).isZero();
        }

        @Test
        @DisplayName("이름이 공백이면 IllegalArgumentException (불변식)")
        void rejectsBlankName() {
            Product onSale = onSaleProduct();

            assertThatThrownBy(() -> onSale.updateInfo(CATEGORY_ID, " ", "설명", null, 0, LATER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("소프트 삭제된 상품은 수정할 수 없다")
        void rejectsUpdateWhenDeleted() {
            Product deleted = onSaleProduct().softDelete(NOW);

            assertThatThrownBy(() -> deleted.updateInfo(CATEGORY_ID, "티셔츠", "설명", null, 0, LATER))
                    .isInstanceOf(InvalidProductStateException.class);
        }
    }
}
