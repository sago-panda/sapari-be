package com.sapari.product.infrastructure.persistence.repository.faq;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.faq.FaqStatus;
import com.sapari.product.domain.model.faq.InquiryImageRef;
import com.sapari.product.domain.model.faq.InquiryType;
import com.sapari.product.domain.model.faq.ProductFaq;
import com.sapari.product.domain.repository.faq.ProductFaqRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductFaqRepositoryImpl} 통합 테스트. 루트 upsert + 첨부 이미지 교체, 상태 전이(답변/소프트삭제), 상품별 조회.
 */
@DisplayName("ProductFaqRepositoryImpl 통합 테스트")
class ProductFaqRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    ProductFaqRepository repository;

    @PersistenceContext
    EntityManager em;

    private ProductFaq reload(UUID id) {
        em.flush();
        em.clear();
        return repository.findById(id)
                .orElseThrow();
    }

    private ProductFaq newFaq(UUID productId, UUID userId, List<InquiryImageRef> images) {
        return ProductFaq.create(productId, userId, InquiryType.PRODUCT, "배송 언제 오나요?", "주문했는데 소식이 없어요",
                true, images);
    }

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("첨부 이미지와 함께 저장하고 그대로 왕복 조회한다(상태 WAITING)")
        void save_round_trips_with_images() {
            UUID productId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            ProductFaq saved = repository.save(newFaq(productId, userId,
                    List.of(InquiryImageRef.of("img/1.jpg", "첨부1.jpg", 1),
                            InquiryImageRef.of("img/2.jpg", "첨부2.jpg", 2))));

            ProductFaq r = reload(saved.id());
            assertThat(r.productId()).isEqualTo(productId);
            assertThat(r.userId()).isEqualTo(userId);
            assertThat(r.inquiryType()).isEqualTo(InquiryType.PRODUCT);
            assertThat(r.title()).isEqualTo("배송 언제 오나요?");
            assertThat(r.content()).isEqualTo("주문했는데 소식이 없어요");
            assertThat(r.isPrivate()).isTrue();
            assertThat(r.status()).isEqualTo(FaqStatus.WAITING);
            assertThat(r.images()).extracting(InquiryImageRef::imageKey)
                    .containsExactlyInAnyOrder("img/1.jpg", "img/2.jpg");
            assertThat(r.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("findByProductId는 해당 상품 문의만 반환한다")
        void findByProductId_filters() {
            UUID productId = UUID.randomUUID();
            repository.save(newFaq(productId, UUID.randomUUID(), List.of()));
            repository.save(newFaq(productId, UUID.randomUUID(), List.of()));
            repository.save(newFaq(UUID.randomUUID(), UUID.randomUUID(), List.of()));
            em.flush();
            em.clear();

            assertThat(repository.findByProductId(productId)).hasSize(2)
                    .allSatisfy(f -> assertThat(f.productId()).isEqualTo(productId));
            assertThat(repository.findByProductId(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transitions {

        @Test
        @DisplayName("answer는 ANSWERED로 전이하고 답변 내용·답변자·답변시각을 저장한다")
        void answer_persists() {
            ProductFaq saved = repository.save(newFaq(UUID.randomUUID(), UUID.randomUUID(), List.of()));
            em.flush();
            em.clear();
            UUID seller = UUID.randomUUID();

            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .answer("내일 출고됩니다", seller, T0));

            ProductFaq r = reload(saved.id());
            assertThat(r.status()).isEqualTo(FaqStatus.ANSWERED);
            assertThat(r.answerContent()).isEqualTo("내일 출고됩니다");
            assertThat(r.answeredBy()).isEqualTo(seller);
            assertThat(r.answeredAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("delete는 deletedAt을 채워 소프트 삭제하고, 행은 그대로 조회된다")
        void delete_soft_deletes() {
            ProductFaq saved = repository.save(newFaq(UUID.randomUUID(), UUID.randomUUID(), List.of()));
            em.flush();
            em.clear();

            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .delete(T0));

            ProductFaq r = reload(saved.id());
            assertThat(r.deletedAt()).isEqualTo(T0);
            assertThat(repository.findById(saved.id())).isPresent(); // 소프트삭제라 행은 조회됨
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("첨부 이미지를 다른 집합으로 전체 교체한다")
        void replaces_images() {
            ProductFaq saved = repository.save(newFaq(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(InquiryImageRef.of("img/old.jpg", null, 1))));
            em.flush();
            em.clear();

            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .images(List.of(InquiryImageRef.of("img/new.jpg", null, 1)))
                    .build());

            ProductFaq r = reload(saved.id());
            assertThat(r.images()).extracting(InquiryImageRef::imageKey)
                    .containsExactly("img/new.jpg");
        }
    }
}
