package com.sapari.chat.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessage;

/**
 * 채팅 이력 읽기 포트 — <b>블로킹</b>(live-app MVC 에서 호출).
 *
 * <p>같은 컬렉션을 리액티브로 쓰는 {@code ChatMessageRepository} 와 따로 두는 이유는 호출 스택이
 * 다르기 때문이다. 이력 호출자는 MVC 라 {@code Mono} 를 받으면 결국 {@code block()} 을 하게 되고,
 * 그러면 이벤트루프와 무관한 자리에 리액티브 왕복이 한 겹 끼기만 한다. 증거 조회가 이미 같은 이유로
 * 블로킹 포트를 따로 갖고 있다({@code ChatMessageEvidenceRepository}).
 *
 * <p><b>제외 대상을 질의에 넘기는 것이 계약이다.</b> 읽어 온 뒤 걸러내면 안 된다 — 이력 응답에
 * {@code hasNext} 가 없어 프론트가 {@code messages.length === size} 로 다음 페이지를 판단하므로,
 * 뒤에서 걸러내면 아직 남았는데도 길이가 모자라 <b>스크롤이 조용히 끝난다</b>.
 */
public interface ChatHistoryRepository {

    /**
     * 커서 역방향 조회 — {@code beforeId} 보다 앞선 메시지를 최신순으로 {@code size} 건.
     *
     * <p><b>정확히 {@code size} 건을 읽는다</b>(공용 커서 규약의 {@code size + 1} 이 아니다).
     * 프론트 계약이 길이로 다음 페이지를 판단하기 때문이고, 하나 더 읽으면 마지막 페이지가 아닌데도
     * 길이가 size 가 되어 "더 있다" 와 "딱 맞다" 를 가를 수 없다. 공용 {@code CursorPage} 에
     * 이 결과를 올리지 말 것 — 그쪽은 size + 1 전제다.
     *
     * <p>커서는 {@code ObjectId} 문자열이다. 형식이 어긋난 값은 던지지 않고 <b>빈 목록</b>으로 답한다 —
     * 갈라 보여 주면 "형식은 맞다" 는 사실이 응답으로 새어 id 를 더듬는 쪽에 신호를 준다.
     *
     * @param beforeId          nullable — 없으면 최신부터
     * @param excludedSenderIds 질의에서 제외할 발신자(강퇴자). 비어 있어도 된다
     */
    List<ChatMessage> findBefore(UUID roomId, String beforeId, int size, Collection<UUID> excludedSenderIds);

    /**
     * VOD 윈도우 조회 — {@code [from, to]} 구간의 메시지를 최신순으로 최대 {@code limit} 건.
     *
     * <p>구간은 재생 위치에서 역산한 <b>절대 시각</b>이다. 경과 시간을 문서에 저장하지 않기로 했으므로
     * ({@code createdAt − startedAt} 으로 계산) 그 변환은 호출자가 한다 — 이 포트는 방송 시작 시각을
     * 모른다.
     */
    List<ChatMessage> findWindow(UUID roomId, Instant from, Instant to, int limit,
            Collection<UUID> excludedSenderIds);
}
