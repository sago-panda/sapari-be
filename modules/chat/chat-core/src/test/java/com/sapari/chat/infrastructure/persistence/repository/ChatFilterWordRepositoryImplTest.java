package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.support.LiveSchema;

/**
 * 사전 정본의 두 <b>네이티브</b> 쿼리를 실제 Postgres에서 고정한다.
 *
 * <p>목으로는 아무것도 검증되지 않는 것이 둘이다. {@code add} 가 "새로 들어갔는가" 를
 * {@code ON CONFLICT (kind, word) DO NOTHING} 의 <b>영향 행 수</b>로 판정하는데 그 수의 의미는
 * 테이블의 유니크 제약이 만든다. 그리고 네이티브 쿼리는 <b>부팅 검증을 안 받는다</b> — JPQL 인
 * {@code findWordsByKind} 는 컨텍스트가 뜰 때 파싱되지만 네이티브 둘은 실제로 돌 때까지 아무도
 * 안 본다. 형제 테이블(강퇴 로그·밴)이 같은 이유로 같은 모양의 테스트를 갖는다.
 *
 * <p>스키마는 운영과 같은 Flyway 파일을 그대로 실행해 만든다. 덤으로 {@code ddl-auto=validate}
 * 가 엔티티와 실제 컬럼을 대조하므로 매핑이 틀리면 컨텍스트가 아예 못 뜬다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("ChatFilterWordRepository — 멱등 추가·정확 삭제·정렬 조회")
class ChatFilterWordRepositoryImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void applyRealSchema() throws Exception {
        LiveSchema.applyTo(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Autowired
    private ChatFilterWordJpaRepository jpaRepository;

    /** 슬라이스는 Spring Data 리포지토리만 올리고 {@code @Repository} 컴포넌트는 스캔하지 않아 직접 엮는다. */
    private ChatFilterWordRepository repository() {
        return new ChatFilterWordRepositoryImpl(jpaRepository);
    }

    private static final UUID ACTOR = ChatConstants.SYSTEM_SENDER_ID;

    @Test
    @DisplayName("⭐ 처음 넣으면 true, 같은 것을 다시 넣으면 false — 행은 하나뿐이다")
    void addIsIdempotentAndReportsWhetherItInserted() {
        assertThat(repository().add(FilterWordKind.PROFANITY, "시발", ACTOR))
                .as("이 신호가 운영자에게 '내 요청이 무언가를 바꿨는가' 를 말한다")
                .isTrue();
        assertThat(repository().add(FilterWordKind.PROFANITY, "시발", ACTOR))
                .as("같은 버튼을 두 번 누르는 것은 오류가 아니라 무동작이어야 한다")
                .isFalse();

        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    /**
     * ⭐ 유니크 제약이 {@code (kind, word)} 라 <b>kind 가 다르면 같은 단어도 들어간다.</b>
     *
     * <p>이것이 설계다 — 같은 문자열이 욕설이면서 화이트리스트일 수 있어야 규칙 (1)이 그 조합을
     * 거부할 대상을 갖는다. 제약이 {@code (word)} 였다면 그 조합이 DB 에서 막히고 규칙 (1)은
     * 도달 불가가 된다.
     */
    @Test
    @DisplayName("⭐ kind 가 다르면 같은 단어도 들어간다 — 유니크가 (kind, word) 다")
    void theUniqueKeyIsPerKind() {
        assertThat(repository().add(FilterWordKind.PROFANITY, "시발점", ACTOR)).isTrue();
        assertThat(repository().add(FilterWordKind.WHITELIST, "시발점", ACTOR)).isTrue();

        assertThat(jpaRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 삭제는 정확 일치다 — 지워졌는지를 돌려준다")
    void removeReportsWhetherItDeleted() {
        repository().add(FilterWordKind.PROFANITY, "시발", ACTOR);

        assertThat(repository().remove(FilterWordKind.PROFANITY, "시발")).isTrue();
        assertThat(repository().remove(FilterWordKind.PROFANITY, "시발"))
                .as("없는 것을 지우는 것도 성공이다 — 멱등이다")
                .isFalse();
        assertThat(jpaRepository.count()).isZero();
    }

    /**
     * ⭐ <b>삭제가 kind 를 가린다.</b> 가리지 않으면 화이트리스트 항목을 지우라는 요청이 같은
     * 문자열의 욕설까지 지우고, 그 순간 그 욕설이 전 플랫폼에서 풀린다.
     */
    @Test
    @DisplayName("⭐ 삭제가 다른 kind 의 같은 단어를 건드리지 않는다")
    void removeDoesNotCrossKinds() {
        repository().add(FilterWordKind.PROFANITY, "시발점", ACTOR);
        repository().add(FilterWordKind.WHITELIST, "시발점", ACTOR);

        assertThat(repository().remove(FilterWordKind.WHITELIST, "시발점")).isTrue();

        assertThat(repository().findWords(FilterWordKind.PROFANITY)).containsExactly("시발점");
        assertThat(repository().findWords(FilterWordKind.WHITELIST)).isEmpty();
    }

    /**
     * ⭐ 조회 순서가 <b>결정론</b>이다. 정렬이 없으면 Postgres 는 순서를 보장하지 않고, 그 순서가
     * 스냅샷 본문의 줄 순서가 되므로 <b>내용이 같은 스냅샷이 매번 다른 바이트</b>가 된다 —
     * 왕복 비교와 두 Pod 의 출력 대조가 둘 다 흔들린다.
     *
     * <p>⚠️ <b>어떤</b> 순서인지는 단언하지 않는다. 처음에 자바의 {@code String} 순서를 박았다가
     * 깨졌다 — Postgres 의 콜레이션이 한글을 자바와 다르게 정렬한다(실측: 입력 지랄·개새끼·시발
     * → 자바는 {@code 개새끼, 시발, 지랄}, 이 컨테이너는 {@code 시발, 지랄, 개새끼}). 박으면
     * 테스트가 컨테이너의 로케일에 묶이고, 그건 운영 DB 와 다를 수 있다. 필요한 성질은
     * <b>한 DB 안에서 같은 입력이 같은 순서</b>이고 그것만 잰다.
     *
     * <p>그래서 시드의 정렬(자바 {@code Collections.sort})과 이 조회의 정렬(Postgres 콜레이션)은
     * <b>서로 다른 순서</b>다. 둘이 같아야 할 이유가 없다 — 시드 정렬은 모든 Pod 이 같은 순서로
     * 행 락을 잡게 하려는 것이고(교착 방지) 이쪽은 읽은 결과가 흔들리지 않게 하려는 것이다.
     * 각자 자기 범위에서 일관되면 충분하다.
     */
    @Test
    @DisplayName("⭐ 조회 순서가 결정론이다 — 같은 내용이 같은 바이트가 되게 한다")
    void findWordsIsDeterministicallyOrdered() {
        for (String word : List.of("지랄", "개새끼", "시발")) {
            repository().add(FilterWordKind.PROFANITY, word, ACTOR);
        }

        List<String> first = List.copyOf(repository().findWords(FilterWordKind.PROFANITY));
        List<String> second = List.copyOf(repository().findWords(FilterWordKind.PROFANITY));

        assertThat(first).containsExactlyInAnyOrder("지랄", "개새끼", "시발");
        assertThat(second)
                .as("두 번 읽어 순서가 다르면 스냅샷 바이트가 매번 달라진다")
                .containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("비어 있으면 isEmpty — 시드 판정이 이 값에 걸린다")
    void isEmptyReflectsTheWholeTable() {
        assertThat(repository().isEmpty()).isTrue();

        // ⚠️ 테이블 <전체>를 본다 — 화이트리스트 행만 있어도 비지 않은 것이다
        repository().add(FilterWordKind.WHITELIST, "시발점", ACTOR);

        assertThat(repository().isEmpty()).isFalse();
        assertThat(repository().findWords(FilterWordKind.PROFANITY)).isEmpty();
    }
}
