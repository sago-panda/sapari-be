package com.sapari.liveapp.controller.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.AddFilterWordCommand;
import com.sapari.chat.command.ListFilterWordsCommand;
import com.sapari.chat.command.RemoveFilterWordCommand;
import com.sapari.chat.port.ManageFilterWordsUseCase;
import com.sapari.chat.view.FilterWordsView;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.liveapp.controller.chat.dto.AddFilterWordRequest;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 욕설 사전 관리 — <b>방 경로 밖</b>이고 ADMIN 전용이다.
 *
 * <p>{@link ChatBanController} 와 같은 이유로 방 경로 아래가 아니다. 사전은 <b>글로벌 1개</b>라
 * 어느 방에서 눌렀는지가 판정을 바꾸지 않고, 방 경로에 두면 다음 사람이 "자기 방이면 고칠 수
 * 있다" 가지를 더하게 된다 — 그건 한 판매자가 전 플랫폼의 마스킹을 바꾸는 길이다.
 *
 * <p><b>왜 이 앱인가.</b> 사전 정본이 Postgres 인데 그것을 평가하는 앱(streaming-app)에는
 * DataSource 가 없다. 정본을 읽고 쓸 수 있는 앱이 여기뿐이고, 쓰기는 Redis 스냅샷 발행으로
 * 끝나므로 함대에 닿는다.
 *
 * <p><b>조회도 ADMIN 이다.</b> 이 목록은 "플랫폼이 무엇을 막는가" 의 전체이고, 우회하려는 사람에게
 * 어떤 표기가 사전에 <b>없는지</b>를 알려 주는 값이다. 그리고 조회가 있어야 삭제가 쓸 수 있다 —
 * 목록을 못 보면 운영자는 무엇을 뺄지 모른다.
 *
 * <p>거부는 {@code ChatException} 이 공통 처리기를 거쳐 나간다 — 권한 없음 403, 보낸 항목이
 * 규칙을 어기면 400, <b>정본에 이미 어기는 항목이 있어</b> 발행할 수 없으면 409다. 뒤의 둘을
 * 가르는 것이 요점이고, 그 이유는 {@code ManageFilterWordsService} javadoc 에 있다.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/chat/filter-words")
public class ChatFilterWordController {

    private final ManageFilterWordsUseCase manageFilterWordsUseCase;

    @GetMapping
    public ResponseEnvelope<FilterWordsView> list(
            @AuthenticationPrincipal LiveUserPrincipal principal) {
        return ResponseEnvelope.success(manageFilterWordsUseCase.list(
                new ListFilterWordsCommand(principal.userId(), principal.role())));
    }

    /**
     * 항목을 더한다.
     *
     * <p>201 이 아니라 200 이다 — 멱등이라 "새로 만들어졌다" 가 보장되지 않는다. 무엇이
     * 일어났는지는 본문의 {@code changed} 가 말한다.
     */
    @PostMapping
    public ResponseEnvelope<FilterWordChangeResponse> add(
            @RequestBody @Valid AddFilterWordRequest request,
            @AuthenticationPrincipal LiveUserPrincipal principal) {
        var change = manageFilterWordsUseCase.add(new AddFilterWordCommand(
                request.kind(), request.word(), principal.userId(), principal.role()));
        return ResponseEnvelope.success(
                new FilterWordChangeResponse(change.changed(), change.version()));
    }

    /**
     * 항목을 뺀다.
     *
     * <p><b>본문이 아니라 쿼리 파라미터</b>다. {@code DELETE} 에 본문을 두는 것은 스펙상 정의되지
     * 않은 영역이고 중간 프록시가 떼어 버릴 수 있다 — 그러면 "무엇을 지울지" 가 사라진 요청이
     * 서버에 도달한다. 사전 항목은 운영 설정이라 접근 로그에 남는 것이 문제가 되지 않는다
     * (개인정보가 아니다).
     */
    @DeleteMapping
    public ResponseEnvelope<FilterWordChangeResponse> remove(
            @RequestParam @NotBlank(message = "kind는 필수입니다.")
            @Size(max = 32, message = "kind가 너무 깁니다.") String kind,
            @RequestParam @NotBlank(message = "word는 필수입니다.")
            @Size(max = 128, message = "word가 너무 깁니다.") String word,
            @AuthenticationPrincipal LiveUserPrincipal principal) {
        var change = manageFilterWordsUseCase.remove(new RemoveFilterWordCommand(
                kind, word, principal.userId(), principal.role()));
        return ResponseEnvelope.success(
                new FilterWordChangeResponse(change.changed(), change.version()));
    }

    /**
     * 무엇이 일어났는가.
     *
     * <p>{@code changed} 는 운영자가 "내 요청이 무언가를 바꿨는지" 와 "이미 그 상태였는지" 를
     * 구분하는 값이다. 둘을 가르지 않으면 반복 클릭과 실패가 같은 응답이 된다.
     *
     * <p>{@code version} 은 이 호출이 발행한 스냅샷 번호다. 받는 쪽 Pod 로그의 {@code version=}
     * 과 맞춰 보면 그 변경이 함대에 실제로 깔렸는지를 확인할 수 있다 — 교체는 거부될 수 있고
     * (정본을 직접 고친 경로가 남긴 항목 때문에) 그때 유일한 신호가 그 로그다.
     */
    public record FilterWordChangeResponse(boolean changed, long version) {
    }
}
