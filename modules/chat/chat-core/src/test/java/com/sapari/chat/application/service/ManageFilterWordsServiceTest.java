package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;
import com.sapari.global.time.TimeProvider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

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

    /** 감사 행에 찍히는 시각 — 주입된 시계가 유일한 출처다. */
    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");

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
                new DictionaryProfanityFilterFactory(),
                new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        given(publishService.publishFromSource()).willReturn(42L);
        given(writeRecorder.add(any(), anyString(), any(), any(), any())).willReturn(true);
        given(writeRecorder.remove(any(), anyString(), any(), any(), any())).willReturn(true);
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
        verify(writeRecorder).add(FilterWordKind.PROFANITY, "지랄", admin, ChatRole.ADMIN, NOW);
        verify(publishService).publishFromSource();
        assertThat(change.changed()).isTrue();
        assertThat(change.version())
                .as("버전이 없으면 운영자가 자기 변경이 함대에 깔릴 번호를 모른다")
                .isEqualTo(42);
    }

    /**
     * ⭐ <b>저장되는 값이 집행되는 값과 같아야 한다.</b>
     *
     * <p>문은 {@code strip} + NFC 뒤의 값을 보는데 쓰기가 원문을 저장하면 한 요청에서 두 결과가
     * 나온다. {@code "시발\n"} 이 가장 나쁘다 — 문은 {@code 시발} 로 보고 200 을 주는데 테이블에는
     * 개행이 든 행이 남고, 그 뒤 <b>모든</b> 스냅샷이 코덱 자기검증에서 깨져 어느 Pod 도 사전을
     * 다시 갈아 끼우지 못한다. 그리고 {@code guardSource} 가 같은 문을 쓰므로 그 상태를 영원히
     * 감지하지 못한다(운영자는 200 과 올라가는 버전만 본다).
     */
    @Test
    @DisplayName("⭐ 보낸 값이 아니라 접은 값을 저장한다 — 개행 한 글자가 전파를 멈추던 자리")
    void theStoredWordIsTheFoldedWord() {
        // given: 네 모양 모두 문은 '시발' 로 본다. 저장도 그래야 한다.
        //   @CsvSource 를 쓰지 않는다 — 값에 개행이 들어가면 CSV 자체가 쪼개져 벡터가
        //   조용히 다른 것이 된다(처음에 그렇게 썼고 인자 하나가 null 로 들어왔다).
        for (String sent : new String[]{"시발\n", "\n시발", "  시발  ", "시발\t"}) {
            sourceIs(Set.of("개새끼"), Set.of());

            service.add(add("PROFANITY", sent, "ADMIN"));
        }

        // then: 네 번 모두 같은 값이 테이블로 간다 — 문이 본 값과 같은 값이다
        verify(writeRecorder, times(4)).add(FilterWordKind.PROFANITY, "시발", admin, ChatRole.ADMIN, NOW);
    }

    /**
     * ⭐ 반대 방향 — 삭제가 <b>성공을 돌려주며 아무것도 지우지 않던</b> 자리.
     *
     * <p>{@code "개새끼 "} 를 저장해 두면 {@code ?word=개새끼} 삭제가 0행이고 응답은
     * {@code changed=false}("이미 그 상태였다") 인데, 실제로는 여전히 전 플랫폼에서 집행 중이다
     * — 집행은 받는 쪽이 다시 접기 때문이다. 접어서 저장하면 삭제 키가 맞는다.
     */
    @Test
    @DisplayName("⭐ 삭제도 접은 값으로 지운다 — 0행 삭제가 성공으로 보이던 자리")
    void theDeletedWordIsTheFoldedWord() {
        // 둘을 둔다 — 하나만 두면 마지막 욕설 거부에 먼저 걸려 이 축을 재지 못한다
        sourceIs(Set.of("개새끼", "시발"), Set.of());

        service.remove(remove("PROFANITY", " 개새끼 ", "ADMIN"));

        verify(writeRecorder).remove(FilterWordKind.PROFANITY, "개새끼", admin, ChatRole.ADMIN, NOW);
    }

    /**
     * NFD 로 들어온 입력(macOS 복붙)도 같은 평면으로 접는다. 접지 않으면
     * {@code uk (kind, word)} 가 원문 기준이라 <b>눈으로 구별 안 되는 두 번째 행</b>이 생긴다.
     */
    @Test
    @DisplayName("⭐ NFD 입력도 NFC 로 접어 저장한다 — 구별 안 되는 두 번째 행을 막는다")
    void anNfdInputIsStoredAsNfc() {
        sourceIs(Set.of("개새끼"), Set.of());
        String nfd = java.text.Normalizer.normalize("시발", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("시발");

        service.add(add("PROFANITY", nfd, "ADMIN"));

        verify(writeRecorder).add(FilterWordKind.PROFANITY, "시발", admin, ChatRole.ADMIN, NOW);
    }

    @Test
    @DisplayName("접으면 아무것도 남지 않는 항목은 400 이다")
    void aWordThatFoldsToNothingIsRejected() {
        sourceIs(Set.of("개새끼"), Set.of());

        // 커맨드의 isBlank 를 지나는 값이어야 이 검사에 도달한다 — BOM 은 isBlank 가 false 다.
        assertThatThrownBy(() -> service.add(add("PROFANITY", "\uFEFF", "ADMIN")))
                .isInstanceOf(FilterWordRejectedException.class);

        verify(writeRecorder, never()).add(any(), anyString(), any(), any(), any());
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

        verify(writeRecorder, never()).add(any(), anyString(), any(), any(), any());
        verify(publishService, never()).publishFromSource();
    }

    /**
     * ⭐ <b>빈 정본에는 첫 단어를 넣을 수 없다 — 그게 붕괴 경로를 막는다.</b>
     *
     * <p>시드가 실패하면(부팅 러너가 그 예외를 삼키고 Pod 는 계속 뜬다) 테이블이 비어 있고 발행도
     * 안 된 상태가 된다. 거기까지는 안전하다 — 함대는 번들 1,078개로 집행한다. 위험한 것은 그
     * <b>다음</b>이다: 운영자가 단어 하나를 추가하면 결과 집합이 {그 단어} 가 되고, 그것이
     * 발행되면 함대의 마스킹이 1,078개에서 <b>1개로</b> 내려앉는다. 운영자는 성공을 받고 목록에서
     * 자기 단어를 보고, 증상("욕설이 거의 안 걸린다")의 원인은 몇 시간 전 부팅 로그의 ERROR
     * 한 줄이다.
     *
     * <p>막는 것은 <b>변경 전 집합 검사</b>다 — 빈 욕설 집합이 사전 문에서 거부되므로
     * {@code add} 가 그 자리에서 409 로 떨어진다. 즉 이 보호는 "빈 테이블" 을 따로 판정해서
     * 생긴 것이 아니라, 귀속을 가르려고 넣은 그 검사가 <b>같이 막아 준</b> 것이다. 그래서
     * 벡터를 명시로 둔다 — 그 검사를 "add 에서 변경 전은 볼 필요 없다" 로 지우는 변경이
     * 이 붕괴를 되살린다.
     *
     * <p>409 가 맞는 이유도 같다. 운영자가 보낸 단어에는 문제가 없고 정본의 <b>상태</b>가 문제다.
     * 그리고 이 상태는 앱 경로로 고칠 수 없다(첫 단어를 넣는 것이 곧 붕괴다) — 복구는 DB 를
     * 고치고 live-app 을 다시 띄워 시드를 돌리는 것이다.
     */
    @Test
    @DisplayName("⭐ 빈 정본에 첫 단어를 넣으면 409 다 — 마스킹이 1개로 내려앉는 것을 막는다")
    void addingTheFirstWordToAnEmptySourceIsRefused() {
        // given: 시드가 실패해 테이블이 비어 있다
        sourceIs(Set.of(), Set.of());

        // when & then
        assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", "ADMIN")))
                .isInstanceOf(FilterWordSourceInvalidException.class);

        verify(writeRecorder, never()).add(any(), anyString(), any(), any(), any());
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

        verify(writeRecorder, never()).add(any(), anyString(), any(), any(), any());
        verify(publishService, never()).publishFromSource();
    }

    /**
     * ⭐ <b>복구 경로가 자기 자신에게 막히지 않는다.</b>
     *
     * <p>제거는 사전 문을 통과시키지 않는다 — "욕설 집합이 비는가" 만 본다. 문을 지나게 하면
     * 어기는 항목을 <b>빼는 요청</b>이 고치려는 그 상태 때문에 거부되고, 전파 동결에서 빠져나올
     * 길이 앱 경로에 없어진다. 이 벡터는 위반이 <b>하나</b>인 경우이고, 바로 아래
     * {@code removingOneOfTwoOffendersSucceeds} 가 둘 이상인 경우를 잡는다 — 결과를 검사하던
     * 동안 통과했던 것이 이쪽이고 막혔던 것이 저쪽이다.
     */
    @Test
    @DisplayName("⭐ 어기는 항목을 빼는 요청은 통과한다 — 그게 동결에서 빠져나오는 길이다")
    void removingTheOffendingEntryIsAllowed() {
        // given: 테이블이 어기고 있고, 지우려는 것이 바로 그 항목이다
        sourceIs(Set.of("시발", OVER_BUDGET), Set.of());

        // when
        var change = service.remove(remove("PROFANITY", OVER_BUDGET, "ADMIN"));

        // then
        verify(writeRecorder).remove(FilterWordKind.PROFANITY, OVER_BUDGET, admin, ChatRole.ADMIN, NOW);
        verify(publishService).publishFromSource();
        assertThat(change.changed()).isTrue();
    }

    /**
     * ⭐ <b>위반이 둘 이상일 때도 하나씩 지울 수 있어야 한다.</b>
     *
     * <p>처음 구현은 제거의 <i>결과</i>를 전체 문에 통과시켰고, 그래서 이 경우에 <b>어느 것도
     * 못 지웠다</b> — 하나를 지우면 남은 것 때문에 거부되고, 이 API 가 한 번에 하나를 지우므로
     * 전파 동결에서 빠져나올 길이 앱 경로에 없어진다. 실측으로 전부 거부였다:
     * <pre>
     *   사전 {시발, 가×17, 나×18, 다×20}  변경 전 통과 false
     *     셋 중 어느 하나를 제거 → 전부 false
     * </pre>
     *
     * <p>그리고 <b>둘 이상이 기본값</b>이다. 이 상태에 닿는 주된 길이 규칙 조이기이고(K 예산
     * 64 → 16), 채워진 테이블에 적용되면 범위 밖 행이 한꺼번에 여럿 위반이 된다.
     */
    @Test
    @DisplayName("⭐ 위반이 둘이어도 하나씩 지울 수 있다 — 아니면 복구 경로가 닫힌다")
    void removingOneOfTwoOffendersSucceeds() {
        // given: 어기는 항목이 둘이다 — 지운 뒤에도 하나가 남는다
        sourceIs(Set.of(OVER_BUDGET, "나".repeat(18)), Set.of());

        // when
        var change = service.remove(remove("PROFANITY", OVER_BUDGET, "ADMIN"));

        // then: 진행한다. 남은 위반은 거부가 아니라 경고로 간다 — 거부하면 복구가 막히고
        // 조용히 넘기면 운영자가 복구가 끝났다고 믿는다.
        verify(writeRecorder).remove(FilterWordKind.PROFANITY, OVER_BUDGET, admin, ChatRole.ADMIN, NOW);
        verify(publishService).publishFromSource();
        assertThat(change.changed()).isTrue();
    }

    /**
     * ⭐ 제거가 막는 것은 <b>하나뿐</b>이다 — 마지막 욕설.
     *
     * <p>사전이 비면 마스킹이 조용히 꺼진다({@code displayMessage == originalMessage}). 이 거부가
     * 없으면 제거 경로가 그 상태를 만들 수 있고, 교체 지점의 문이 받아 주지만 그때는 이미
     * 정본이 비어 있어 모든 Pod 이 옛 사전으로 굳는다.
     */
    @Test
    @DisplayName("⭐ 마지막 욕설은 지울 수 없다 — 사전이 비면 마스킹이 조용히 꺼진다")
    void theLastProfanityCannotBeRemoved() {
        // given: 욕설이 하나뿐이다
        sourceIs(Set.of("시발"), Set.of("시발점"));

        assertThatThrownBy(() -> service.remove(remove("PROFANITY", "시발", "ADMIN")))
                .isInstanceOf(FilterWordRejectedException.class);

        verify(writeRecorder, never()).remove(any(), anyString(), any(), any(), any());
        verify(publishService, never()).publishFromSource();
    }

    /** 화이트리스트를 전부 지우는 것은 막지 않는다 — 비어도 마스킹은 돈다(보존만 사라진다). */
    @Test
    @DisplayName("화이트리스트는 전부 지울 수 있다 — 비어도 마스킹은 꺼지지 않는다")
    void theLastWhitelistEntryMayBeRemoved() {
        sourceIs(Set.of("시발"), Set.of("시발점"));

        var change = service.remove(remove("WHITELIST", "시발점", "ADMIN"));

        verify(writeRecorder).remove(FilterWordKind.WHITELIST, "시발점", admin, ChatRole.ADMIN, NOW);
        assertThat(change.changed()).isTrue();
    }

    /**
     * 멱등 호출에도 발행한다. 건너뛰면 스냅샷이 유실된 상태를 되돌릴 경로가 하나 줄어든다 —
     * 운영자가 "이미 있는 단어를 다시 눌러" 복구하는 것이 가장 자연스러운 손짓이다.
     */
    @Test
    @DisplayName("이미 있는 단어를 다시 더해도 발행한다 — changed=false 로 알려 준다")
    void anIdempotentAddStillPublishes() {
        sourceIs(Set.of("시발"), Set.of());
        given(writeRecorder.add(any(), anyString(), any(), any(), any())).willReturn(false);

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

        // "거부는 함대에 깔리지 않는다" 를 네 갈래 전부에 적는다 — 나머지 셋(add 409 · add 400 ·
        // 마지막 욕설)이 발행까지 단언하는데 여기만 쓰기만 봤다. 권한이 먼저 걸려 오늘은 도달
        // 불가지만, 같은 불변식을 한 갈래에만 안 적으면 그 갈래가 먼저 느슨해진다.
        verify(writeRecorder, never()).add(any(), anyString(), any(), any(), any());
        verify(writeRecorder, never()).remove(any(), anyString(), any(), any(), any());
        verify(publishService, never()).publishFromSource();
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

        verify(writeRecorder).add(FilterWordKind.PROFANITY, "지랄", admin, ChatRole.ADMIN, NOW);
    }

    /**
     * ⭐ <b>커밋 뒤의 진단 읽기가 본선을 죽이지 않는다.</b>
     *
     * <p>{@code warnIfCommittedSourceViolates} 의 두 읽기가 {@code try} <b>밖</b>에 있었고 호출
     * 위치도 발행 <b>앞</b>이었다. 그 읽기의 일시 오류가 <b>쓰기는 커밋됐는데 발행은 안 되고
     * 응답은 일반 500</b> 을 만들었다 — 진단이 자기가 알리려던 바로 그 상태를 만드는 모양이다.
     *
     * <p>지금은 둘 다 고쳤다: 호출이 <b>발행 뒤</b>로 갔고(그래서 진단이 전파를 잃게 할 수
     * 없다) 읽기도 {@code try} 안이다. 이 벡터는 그 둘이 되돌려지는 것을 같이 막는다 — 호출만
     * 앞으로 되돌리면 삼킴이 남아 통과하지만, {@code try} 를 걷으면 그때 발행을 잃는다.
     */
    @Test
    @DisplayName("⭐ 커밋 뒤 진단 읽기가 실패해도 발행은 간다 — 진단이 본선을 죽이면 안 된다")
    void aFailedDiagnosticReadDoesNotSkipThePublish() {
        // given: 선검사의 두 읽기는 되고, 커밋 뒤 재검사의 두 읽기가 터진다
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(Set.of("시발"))
                .willThrow(new org.springframework.dao.QueryTimeoutException("일시 DB 오류"));
        given(repository.findWords(FilterWordKind.WHITELIST))
                .willReturn(Set.of())
                .willThrow(new org.springframework.dao.QueryTimeoutException("일시 DB 오류"));

        // when
        var change = service.add(add("PROFANITY", "지랄", "ADMIN"));

        // then
        verify(publishService)
                .publishFromSource();
        assertThat(change.version())
                .as("진단 읽기가 실패했다고 발행을 건너뛰면 정본과 함대가 갈린 채로 200 이 나간다")
                .isEqualTo(42);
    }

    /**
     * ⭐ <b>발행 실패가 무엇을 잃었는지와 함께 적힌다.</b>
     *
     * <p>성공 로그가 발행 <b>뒤</b>에 있다. 그래서 발행이 실패하면 <정본은 바뀌었는데 함대는
     * 모르는> 상태가 로그 한 줄 없이 일반 500 으로만 나갔다. 복구 수단이 같은 요청의 재시도인데
     * (쓰기가 멱등이고 멱등 호출도 발행한다) 그 사실이 어디에도 없으면 누른 사람은 재시도할
     * 이유를 모른다. 밴 해제의 미러 삭제에서 같은 모양을 이미 한 번 고쳤다.
     */
    @Test
    @DisplayName("⭐ 발행이 실패하면 무엇을 잃었는지 적고 올린다 — 조용한 500 이면 복구 안내가 없다")
    void aFailedPublishIsLoggedWithWhatWasLost() {
        sourceIs(Set.of("시발"), Set.of());
        given(publishService.publishFromSource())
                .willThrow(new IllegalStateException("발행이 5회 밀렸다"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ManageFilterWordsService.class);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", "ADMIN")))
                    .as("삼키면 운영자가 '깔렸다' 고 믿는데 함대는 그 단어를 모른다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("이 상태가 조용하면 복구 수단(멱등 재시도)이 아무에게도 전달되지 않는다")
                    .anySatisfy(message -> assertThat(message)
                            .contains("발행이 실패했다")
                            .contains("다시 보내면 된다"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>삼키는 범위가 삼키는 근거와 같다.</b>
     *
     * <p>잡는 타입이 {@code RuntimeException} 이었다. 삼킴의 근거는 "읽기가 일시적으로 실패할
     * 수 있다" 인데 그 범위는 {@code DataAccessException} 이고, 넓게 잡으면 이 경로의 코드 결함
     * (NPE 등)까지 WARN 한 줄로 사라진다 — 진단이 자기 버그를 숨기는 모양이다.
     */
    @Test
    @DisplayName("⭐ 진단 경로의 코드 결함은 삼키지 않는다 — 삼킴은 DB 실패에만 적용된다")
    void aCodeDefectInTheDiagnosticIsNotSwallowed() {
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(Set.of("시발"))
                .willThrow(new IllegalStateException("진단 경로의 코드 결함"));
        given(repository.findWords(FilterWordKind.WHITELIST))
                .willReturn(Set.of())
                .willThrow(new IllegalStateException("진단 경로의 코드 결함"));

        assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", "ADMIN")))
                .as("넓게 잡으면 이 결함이 WARN 한 줄로 사라진다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("코드 결함");
    }

    /**
     * ⭐ <b>진단이 발행 뒤라는 것을 순서로 못 박는다.</b>
     *
     * <p>이 순서를 옮긴 커밋에 <b>어떤 테스트도 걸리지 않았다</b> — 되돌려 전체 수트를 돌리면
     * 895건 0실패였다. 삼킴이 남아 있어서 "발행을 잃는가" 축이 통과하기 때문이다.
     *
     * <p>그런데 삼킴을 {@code DataAccessException} 으로 좁힌 뒤에는 남는 벡터가 있다 — 진단
     * 경로의 {@code IllegalStateException}(바로 위 테스트가 못 박은 그 타입)이 발행 <b>앞</b>에
     * 있으면 발행을 통째로 잃는다. 그래서 순서 자체를 단언한다.
     */
    @Test
    @DisplayName("⭐ 발행 → 감사 로그 → 진단 순서다 — 진단이 앞이면 발행을, 로그 앞이면 감사를 잃는다")
    void theDiagnosticRunsAfterThePublish() {
        sourceIs(Set.of("시발"), Set.of());

        service.add(add("PROFANITY", "지랄", "ADMIN"));

        // 선검사 읽기 2회 → 쓰기 → 발행 → 진단 읽기 2회.
        var order = inOrder(repository, writeRecorder, publishService);
        order.verify(repository).findWords(FilterWordKind.PROFANITY);
        order.verify(repository).findWords(FilterWordKind.WHITELIST);
        order.verify(writeRecorder).add(FilterWordKind.PROFANITY, "지랄", admin, ChatRole.ADMIN, NOW);
        order.verify(publishService).publishFromSource();
        order.verify(repository, times(2)).findWords(any());
    }

    /**
     * ⭐ <b>감사 로그가 진단보다 앞이라는 것을 로그 축에서 잰다.</b>
     *
     * <p>{@code InOrder} 는 발행↔진단 축만 못 박는다 — Mockito 가 {@code log.info} 를 못 보므로
     * <b>감사 로그 축은 0</b>이었다. 실측: {@code log.info} 를 진단 뒤로 되돌려도 25건 0실패다.
     * 그래서 그 자리의 {@code @DisplayName}("로그 앞이면 감사를 잃는다")이 재지 않는 것을
     * 재는 것처럼 적혀 있었다.
     *
     * <p>잃는 것은 이것이다 — 진단이 {@code DataAccessException} <b>밖</b>을 던지면(바로 위
     * 테스트가 못 박은 {@code IllegalStateException}) 쓰기와 발행이 둘 다 성공한 권한 있는
     * 조정 변경이 <b>누가 무엇을 바꿨는지 한 줄도 없이</b> 일반 500 으로 나간다. 발행이
     * 성공했으니 {@code publishOrLogLoss} 의 ERROR 도 안 찍힌다.
     */
    @Test
    @DisplayName("⭐ 진단이 던져도 감사 로그는 이미 남아 있다 — 성공한 제재 변경이 흔적 없이 사라지면 안 된다")
    void theAuditLineSurvivesAThrowingDiagnostic() {
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(Set.of("시발"))
                .willThrow(new IllegalStateException("진단 경로의 코드 결함"));
        given(repository.findWords(FilterWordKind.WHITELIST))
                .willReturn(Set.of())
                .willThrow(new IllegalStateException("진단 경로의 코드 결함"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ManageFilterWordsService.class);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.add(add("PROFANITY", "지랄", "ADMIN")))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("쓰기·발행이 둘 다 성공했는데 누가 무엇을 바꿨는지가 아무 데도 없으면 "
                            + "제재 변경의 흔적이 사라진다")
                    .anySatisfy(message -> assertThat(message)
                            .contains("사전에 항목을 더했다")
                            .contains("version=42"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
