package com.sapari.chat.application.service;

import java.util.LinkedHashSet;
import java.util.Set;

import com.sapari.chat.application.port.ProfanityFilterFactory;
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
import com.sapari.chat.port.ManageFilterWordsUseCase;
import com.sapari.chat.view.FilterWordsView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 욕설 사전을 운영자가 고치는 경로 — <b>사전의 역경로</b>다.
 *
 * <p>전에는 사전을 바꾸는 유일한 길이 배포였다. 방송 중에 새 우회 표현이 돌기 시작하면 그날은
 * 손쓸 방법이 없었고, 반대 방향(사전 항목이 정상어를 걸어 오탐을 내는 것)도 그 항목을 빼는
 * 배포밖에 없었다 — {@code sex}·{@code siva} 에서 이미 두 번 그 길로 갔다.
 *
 * <p><b>검사가 보는 것은 보낸 항목이 아니라 <i>결과 사전 전체</i>다.</b> 보낸 항목만 보면 정본에
 * 이미 어기는 행이 하나 있을 때 전파가 동결된다 — 쓰기는 통과해 성공이 나가고, 모든 Pod 이
 * 스냅샷을 읽다가 그 옛 행에 걸려 교체를 거부한다. 운영자는 성공을 받고 목록에서 자기 단어를
 * 보는데 어느 Pod 도 그것을 집행하지 않고, 그 뒤의 모든 변경도 같다.
 *
 * <p><b>그래서 변경 전 집합도 따로 검사한다.</b> 그것이 이미 어기고 있으면 원인은 운영자의 입력이
 * 아니므로 {@link FilterWordSourceInvalidException}(409)이고, 멀쩡한데 결과가 어기면 차이가 보낸
 * 항목 하나뿐이므로 {@link FilterWordRejectedException}(400)이다. 하나로 뭉개면 멀쩡한 단어를
 * 보낸 운영자에게 "당신 단어가 틀렸다" 는 거짓 안내가 간다.
 *
 * <p><b>제거는 이 문을 통과시키지 않는다</b> — "욕설 집합이 비는가" 만 본다. 결과를 검사하면
 * 복구가 자기 자신에게 막히고(위반이 둘 이상이면 하나씩 지우는 요청이 전부 거부된다) 그 검사가
 * 거기서 버는 것도 없다(제거는 새 위반을 만들 수 없다). 근거와 실측은 {@link #guardRemoval} 에 있다.
 *
 * <p><b>검사가 트라이를 한 번 세운다</b>(1,078 항목에서 p50 0.54 ms). 문을 쓰면서 그 비용을 피할
 * 수 없는데, 문을 둘로 나누면(검사만 하는 것 / 만드는 것) 번들·설정·전파가 지나는 문과 갈릴
 * 위험이 생긴다. 관리자 쓰기는 사람 타이핑 빈도라 0.54 ms × 2 를 그 위험과 바꾸지 않는다.
 *
 * <p><b>쓰기는 발행으로 끝난다.</b> 정본만 고치고 발행하지 않으면 함대가 그 변경을 영원히 모른다 —
 * 받는 쪽이 버전을 보므로, 버전이 안 오르면 아무 일도 일어나지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class ManageFilterWordsService implements ManageFilterWordsUseCase {

    /** 검사 실패 메시지에 실을 출처 — 세 입구 중 어디서 떨어졌는지가 로그에 가야 한다. */
    private static final String ADMIN_ORIGIN = "chat:filter-word:admin";
    private static final String SOURCE_ORIGIN = "chat:filter-word:source";

    private final ChatPermissionPolicy permissionPolicy;
    private final ChatFilterWordRepository repository;
    private final ChatFilterWordWriteRecorder writeRecorder;
    private final ProfanityDictionaryPublishService publishService;
    private final ProfanityFilterFactory filterFactory;

    @Override
    public FilterWordChange add(AddFilterWordCommand command) {
        requireAdmin(command.actorRole());
        FilterWordKind kind = parseKind(command.kind());

        Set<String> words = repository.findWords(FilterWordKind.PROFANITY);
        Set<String> whitelist = repository.findWords(FilterWordKind.WHITELIST);
        // 변경 전이 이미 어기는가 — 그렇다면 원인은 운영자의 입력이 아니다.
        guardSource(words, whitelist);

        Set<String> nextWords = plus(words, kind == FilterWordKind.PROFANITY ? command.word() : null);
        Set<String> nextWhitelist =
                plus(whitelist, kind == FilterWordKind.WHITELIST ? command.word() : null);
        // 변경 전이 멀쩡했으므로 여기서 떨어지면 차이는 보낸 항목 하나뿐이다.
        guardRequested(nextWords, nextWhitelist, command.word());

        boolean changed = writeRecorder.add(kind, command.word(), command.actorId());
        // 멱등 호출(이미 있던 단어)에도 발행한다 — 건너뛰면 스냅샷이 유실된 상태를 되돌릴
        // 경로가 하나 줄어든다.
        long version = publishService.publishFromSource();
        log.info("사전에 항목을 더했다 — kind={} changed={} version={} actorId={}",
                kind, changed, version, command.actorId());
        return new FilterWordChange(changed, version);
    }

    @Override
    public FilterWordChange remove(RemoveFilterWordCommand command) {
        requireAdmin(command.actorRole());
        FilterWordKind kind = parseKind(command.kind());

        Set<String> words = repository.findWords(FilterWordKind.PROFANITY);
        Set<String> whitelist = repository.findWords(FilterWordKind.WHITELIST);
        Set<String> nextWords = minus(words, kind == FilterWordKind.PROFANITY ? command.word() : null);
        Set<String> nextWhitelist =
                minus(whitelist, kind == FilterWordKind.WHITELIST ? command.word() : null);
        guardRemoval(nextWords, nextWhitelist);

        boolean changed = writeRecorder.remove(kind, command.word());
        long version = publishService.publishFromSource();
        log.info("사전에서 항목을 뺐다 — kind={} changed={} version={} actorId={}",
                kind, changed, version, command.actorId());
        return new FilterWordChange(changed, version);
    }

    @Override
    public FilterWordsView list(ListFilterWordsCommand command) {
        requireAdmin(command.actorRole());
        return new FilterWordsView(
                repository.findWords(FilterWordKind.PROFANITY),
                repository.findWords(FilterWordKind.WHITELIST));
    }

    private void requireAdmin(String actorRole) {
        ChatRole role = ChatRole.fromPlatformRole(actorRole)
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할: 사전 변경 거부 actorRole=" + actorRole));
        if (!permissionPolicy.canManageFilterWords(role)) {
            throw new ChatPermissionDeniedException(
                    "사전 변경 권한 없음 role=" + role);
        }
    }

    /**
     * {@code kind} 는 요청자가 정하는 값이라 모르는 이름은 400 이다.
     *
     * <p>{@code valueOf} 의 {@code IllegalArgumentException} 을 그대로 올리면 전역 핸들러에 그 타입
     * 처리가 없어 <b>클라 잘못이 500 + Unhandled exception 로그</b>가 된다 — 이력 조회 쪽에서
     * 같은 이유로 번역을 둔 것과 같은 자리다.
     */
    private FilterWordKind parseKind(String kind) {
        try {
            return FilterWordKind.valueOf(kind.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FilterWordRejectedException(
                    "알 수 없는 kind — PROFANITY 또는 WHITELIST 여야 한다. 받은 길이=" + kind.length(), e);
        }
    }

    /** 변경 전(또는 제거 후) 집합이 문을 지나는가. 떨어지면 원인이 운영자의 입력이 아니다. */
    private void guardSource(Set<String> words, Set<String> whitelist) {
        try {
            filterFactory.from(SOURCE_ORIGIN, words, whitelist);
        } catch (IllegalStateException e) {
            throw new FilterWordSourceInvalidException(
                    "정본이 사전 문을 통과하지 못한다 — 이 상태로는 발행해도 전 Pod 가 교체를 "
                            + "거부한다. 사유=" + e.getMessage(), e);
        }
    }

    /**
     * 제거가 허용되는가 — <b>전체 문을 통과시키지 않는다.</b>
     *
     * <p>처음에는 결과 집합을 전체 문에 통과시켰다. 그게 복구 경로를 자기 자신에게 막았다:
     * 위반이 <b>둘 이상</b>이면 하나씩 지우는 요청이 전부 "남은 것 때문에" 거부되고, 엔드포인트가
     * 한 번에 하나를 지우므로 <b>어느 것도 못 지운다</b>. 실측:
     * <pre>
     *   사전 {시발, 병신, 개새끼} · 화이트 {시발, 병신}   변경 전 통과 false
     *     화이트 '시발' 제거 → false      화이트 '병신' 제거 → false
     *     둘 다 제거 → true
     *   사전 {시발, 가×17, 나×18, 다×20}(예산 밖 셋)      변경 전 통과 false
     *     셋 중 어느 하나를 제거 → 전부 false
     * </pre>
     * 그리고 둘 이상이 <b>그 시나리오의 기본값</b>이다 — 이 상태에 닿는 주된 길이 규칙 조이기이고
     * (이 브랜치의 K 예산 64 → 16 이 바로 그것), 채워진 테이블에 적용되면 범위 밖 행이 한꺼번에
     * 여럿 위반이 된다.
     *
     * <p><b>전체 문이 거기서 버는 것이 없다 — 제거는 새 위반을 만들 수 없다.</b> 구조로 그렇다:
     * 규칙 (1)은 {@code 화이트 항목 ∈ 사전} 일 때 걸리는데 제거는 사전을 줄이므로 그 포함을
     * 새로 만들 수 없고, 규칙 (3)은 욕설을 뺀 <i>잔여</i>를 보므로 단어를 지우면 잔여가 길어져
     * 더 통과하기 쉬워지고(품는 욕설이 0건이 되면 아예 건너뛴다), 모양·예산 검사는 항목별이라
     * 제거와 무관하다. 번들 전수로도 같다 — 단일 제거 1,116건(욕설 1,078 + 화이트 38) 중 새
     * 위반 <b>0건</b>. (양성 대조군: 욕설 0개 집합은 거부된다 — 전수의 0 이 "도구가 못 봤다" 가
     * 아니다.)
     *
     * <p><b>막아야 하는 것은 하나다 — 마지막 욕설.</b> 사전이 비면 마스킹이 조용히 꺼진다
     * ({@code displayMessage == originalMessage}). 그 판정을 문에 맡기지 않고 여기서 먼저 보는
     * 이유는 <b>귀속</b>이다: 문은 빈 집합 거부보다 항목별 모양 거부를 먼저 던질 수 있어, 문에
     * 맡기면 "마지막 욕설을 지우려 한다" 를 엉뚱한 사유에 붙일 수 있다. 문은 교체 지점에서
     * 여전히 백스톱으로 선다.
     *
     * <p>남은 위반은 <b>경고로 낸다.</b> 거부하면 복구가 막히고, 조용히 넘기면 운영자가 복구가
     * 끝났다고 믿는다 — 그 둘 사이가 이 로그다.
     */
    private void guardRemoval(Set<String> words, Set<String> whitelist) {
        if (words.isEmpty()) {
            throw new FilterWordRejectedException(
                    "마지막 욕설을 지울 수 없다 — 사전이 비면 마스킹이 조용히 꺼진다"
                            + "(displayMessage == originalMessage).");
        }
        try {
            filterFactory.from(SOURCE_ORIGIN, words, whitelist);
        } catch (IllegalStateException e) {
            log.warn("제거 후에도 정본이 사전 문을 통과하지 못한다 — 복구가 끝나지 않았다. "
                            + "남은 욕설 {}개 · 화이트리스트 {}개 사유={}",
                    words.size(), whitelist.size(), e.getMessage());
        }
    }

    /** 결과 집합이 문을 지나는가. 변경 전이 멀쩡했으므로 떨어지면 보낸 항목이 원인이다. */
    private void guardRequested(Set<String> words, Set<String> whitelist, String requested) {
        try {
            filterFactory.from(ADMIN_ORIGIN, words, whitelist);
        } catch (IllegalStateException e) {
            throw new FilterWordRejectedException(
                    "보낸 항목이 사전 문을 통과하지 못한다 — 항목 길이=" + requested.length()
                            + " 사유=" + e.getMessage(), e);
        }
    }

    /** {@code null} 이면 그대로 — 두 집합 중 한쪽에만 더하기 때문이다. 순서를 보존한다. */
    private static Set<String> plus(Set<String> base, String added) {
        Set<String> out = new LinkedHashSet<>(base);
        if (added != null) {
            out.add(added);
        }
        return out;
    }

    private static Set<String> minus(Set<String> base, String removed) {
        Set<String> out = new LinkedHashSet<>(base);
        if (removed != null) {
            out.remove(removed);
        }
        return out;
    }
}
