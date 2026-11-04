package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sapari.chat.command.AddFilterWordCommand;
import com.sapari.chat.command.ListFilterWordsCommand;
import com.sapari.chat.command.RemoveFilterWordCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.FilterWordRejectedException;
import com.sapari.chat.domain.exception.FilterWordSourceInvalidException;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;

/**
 * 사전 관리 — <b>거부의 원인을 운영자의 입력과 정본 중 어느 쪽으로 돌리는지</b>를 잰다.
 *
 * <p>검사가 결과 사전 <b>전체</b>를 보기 때문에, 정본에 이미 규칙을 어기는 행이 하나 있으면
 * 운영자가 보낸 항목이 멀쩡해도 검사가 떨어진다. 그때 "당신 단어가 틀렸다" 로 답하면 멀쩡한
 * 단어를 고치려 들게 되고, 실제 원인(옛 행)은 영원히 안 보인다.
 *
 * <p>그래서 서비스가 <b>변경 전</b> 집합을 먼저 검사해 둘을 가른다. 그 갈림이 이 클래스에서
 * 가장 세게 박는 축이다.
 *
 * <p>사전 문은 <b>실제 구현</b>을 쓴다. 목으로 바꾸면 "예산을 넘는 항목을 거부한다" 가 목 설정을
 * 재는 테스트가 되고, 그 거부가 실제로 그 문에서 나오는지는 안 재진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("사전 관리 — 거부의 원인을 입력과 정본으로 가른다")
class ManageFilterWordsServiceTest {

    /** K 예산(16) 밖이라 사전 문이 거부하는 항목. */
    private static final String OVER_BUDGET = "가".repeat(17);

    @Mock
    private ChatFilterWordRepository repository;
    @Mock
    private ChatFilterWordWriteRecorder writeRecorder;
    @Mock
    private ProfanityDictionaryPublishService publishService;

    private ManageFilterWordsService service;

    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ManageFilterWordsService(
                new ChatPermissionPolicy(), repository, writeRecorder, publishService,
                new DictionaryProfanityFilterFactory());
        given(publishService.publishFromSource()).willReturn(42L);
        given(writeRecorder.add(any(), anyString(), any())).willReturn(true);
        given(writeRecorder.remove(any(), anyString())).willReturn(true);
    }

    private void sourceIs(Set<String> words, Set<String> whitelist) {
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(words);
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(whitelist);
    }

    private AddFilterWordCommand add(String kind, String word, String role) {
        return new AddFilterWordCommand(kind, word, admin, role);
    }

    private RemoveFilterWordCommand remove(String kind, String word, String role) {
        return new RemoveFilterWordCommand(kind, word, admin, role);
    }

    @Test
    @DisplayName("⭐ 관리자가 더하면 정본에 쓰고 발행한다 — 버전을 돌려준다")
    void anAdminAddAddsAndPublishes() {
        // given
        sourceIs(Set.of("시발"), Set.of("시발점"));

        // when
        var change = service.add(add("PROFANITY", "지랄", "ADMIN"));

        // then
        verify(writeRecorder).add(FilterWordKind.PROFANITY, "지랄", admin);
        verify(publishService).publishFromSource();
        assertThat(change.changed()).isTrue();
        assertThat(change.version())
                .as("버전이 없으면 운영자가 자기 변경이 함대에 깔릴 번호를 모른다")
                .isEqualTo(42);
    }

    /**
     * ⭐ <b>이 테스트가 전파 동결을 막는다.</b>
     *
     * <p>검사가 결과 전체를 보므로, 정본에 어기는 행이 하나 있으면 멀쩡한 단어를 더하는 요청도
     * 떨어진다. 그 자체는 맞는 동작이다 — 통과시키면 쓰기는 성공하고 모든 Pod 이 그 옛 행 때문에
     * 교체를 거부해서, 운영자는 성공을 받고 목록에서 자기 단어를 보는데 어느 Pod 도 집행하지
     * 않는다. 그 뒤의 모든 변경도 같다.
     *
     * <p>⭐ 다만 <b>409 여야 한다</b>. 원인이 운영자의 입력이 아니기 때문이다. 400 으로 뭉개면
     * 멀쩡한 단어를 고치려 들게 되고 실제 원인은 안 보인다.
     *
     * <p>그리고 쓰기·발행이 <b>일어나지 않아야</b> 한다 — 거부의 요점이 그 상태를 함대에 깔지
     * 않는 것이다.
     */
    @Test
    @DisplayName("⭐ 정본이 이미 규칙을 어기면 멀쩡한 단어도 409 다 — 원인이 입력이 아니다")
    void anAlreadyInvalidSourceIsReportedAsTheSourcesFault() {
        // given: 예산 밖 항목이 테이블에 이미 있다(직접 SQL 이나 규칙 조이기로 생긴 상태)
        sourceIs(Set.of("시발", OVER_BUDGET), Set.of());

        // when & then
        assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", "ADMIN")))
                .isInstanceOf(FilterWordSourceInvalidException.class);

        verify(writeRecorder, never()).add(any(), anyString(), any());
        verify(publishService, never()).publishFromSource();
    }

    /**
     * ⭐ 반대 축 — 정본이 멀쩡하면 결과가 어기는 원인은 <b>보낸 항목 하나</b>뿐이다.
     *
     * <p>이 둘이 같은 코드로 나가면 안 되는 이유가 위 테스트의 javadoc 에 있다.
     */
    @Test
    @DisplayName("⭐ 정본이 멀쩡한데 결과가 어기면 400 이다 — 차이는 보낸 항목 하나다")
    void aBadRequestedWordIsReportedAsTheCallersFault() {
        // given
        sourceIs(Set.of("시발"), Set.of());

        // when & then
        assertThatThrownBy(() -> service.add(add("PROFANITY", OVER_BUDGET, "ADMIN")))
                .isInstanceOf(FilterWordRejectedException.class);

        verify(writeRecorder, never()).add(any(), anyString(), any());
        verify(publishService, never()).publishFromSource();
    }

    /**
     * ⭐ <b>복구 경로가 자기 자신에게 막히지 않는다.</b>
     *
     * <p>제거는 변경 전을 검사하지 않고 결과만 본다. 검사했다면 어기는 항목을 <b>빼는 요청</b>이
     * 고치려는 그 상태 때문에 거부되고, 전파 동결에서 빠져나올 길이 앱 경로에 없어진다.
     */
    @Test
    @DisplayName("⭐ 어기는 항목을 빼는 요청은 통과한다 — 그게 동결에서 빠져나오는 길이다")
    void removingTheOffendingEntryIsAllowed() {
        // given: 테이블이 어기고 있고, 지우려는 것이 바로 그 항목이다
        sourceIs(Set.of("시발", OVER_BUDGET), Set.of());

        // when
        var change = service.remove(remove("PROFANITY", OVER_BUDGET, "ADMIN"));

        // then
        verify(writeRecorder).remove(FilterWordKind.PROFANITY, OVER_BUDGET);
        verify(publishService).publishFromSource();
        assertThat(change.changed()).isTrue();
    }

    @Test
    @DisplayName("빼고도 여전히 어기면 409 다 — 남은 항목 때문이고 운영자가 보낸 것이 아니다")
    void removingOneOfTwoOffendersStillFails() {
        // given: 어기는 항목이 둘이다
        sourceIs(Set.of(OVER_BUDGET, "나".repeat(18)), Set.of());

        assertThatThrownBy(() -> service.remove(remove("PROFANITY", OVER_BUDGET, "ADMIN")))
                .isInstanceOf(FilterWordSourceInvalidException.class);

        verify(writeRecorder, never()).remove(any(), anyString());
    }

    /**
     * 멱등 호출에도 발행한다. 건너뛰면 스냅샷이 유실된 상태를 되돌릴 경로가 하나 줄어든다 —
     * 운영자가 "이미 있는 단어를 다시 눌러" 복구하는 것이 가장 자연스러운 손짓이다.
     */
    @Test
    @DisplayName("이미 있는 단어를 다시 더해도 발행한다 — changed=false 로 알려 준다")
    void anIdempotentAddStillPublishes() {
        sourceIs(Set.of("시발"), Set.of());
        given(writeRecorder.add(any(), anyString(), any())).willReturn(false);

        var change = service.add(add("PROFANITY", "시발", "ADMIN"));

        assertThat(change.changed()).isFalse();
        verify(publishService).publishFromSource();
    }

    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"SELLER", "USER"})
    @DisplayName("⭐ 관리자가 아니면 사전을 고칠 수 없다 — 한 줄이 전 방의 마스킹을 바꾼다")
    void onlyAdminsMayManageTheDictionary(String role) {
        sourceIs(Set.of("시발"), Set.of());

        assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", role)))
                .isInstanceOf(ChatPermissionDeniedException.class);
        assertThatThrownBy(() -> service.remove(remove("PROFANITY", "시발", role)))
                .isInstanceOf(ChatPermissionDeniedException.class);
        assertThatThrownBy(() -> service.list(new ListFilterWordsCommand(admin, role)))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verify(writeRecorder, never()).add(any(), anyString(), any());
    }

    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"OPERATOR", "admin", ""})
    @DisplayName("모르는 역할 이름은 정본을 읽기도 전에 거부한다")
    void unknownRoleIsRejectedBeforeReadingTheSource(String role) {
        // "" 는 커맨드 컴팩트 생성자에서 끊기므로 서비스까지 오지 않는다 — 그래서 두 예외 중
        // 하나를 허용한다. 둘 다 "거부" 이고, 어느 레이어가 끊는지는 이 테스트가 볼 것이 아니다.
        assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", role)))
                .isInstanceOfAny(ChatPermissionDeniedException.class, IllegalArgumentException.class);

        verify(repository, never()).findWords(any());
    }

    @Test
    @DisplayName("모르는 kind 는 400 이다 — valueOf 의 예외를 그대로 올리면 500 이 된다")
    void anUnknownKindIsABadRequest() {
        assertThatThrownBy(() -> service.add(add("BANWORD", "지랄", "ADMIN")))
                .isInstanceOf(FilterWordRejectedException.class);
    }

    @Test
    @DisplayName("kind 는 대소문자를 가리지 않는다 — 운영 도구가 소문자로 보내는 것이 흔하다")
    void kindIsCaseInsensitive() {
        sourceIs(Set.of("시발"), Set.of());

        service.add(add("profanity", "지랄", "ADMIN"));

        verify(writeRecorder).add(FilterWordKind.PROFANITY, "지랄", admin);
    }

    @Test
    @DisplayName("목록은 두 집합을 그대로 돌려준다 — 조회가 있어야 삭제가 쓸 수 있다")
    void listReturnsBothSets() {
        sourceIs(Set.of("시발"), Set.of("시발점"));

        var view = service.list(new ListFilterWordsCommand(admin, "ADMIN"));

        assertThat(view.words()).containsExactly("시발");
        assertThat(view.whitelist()).containsExactly("시발점");
    }
}
