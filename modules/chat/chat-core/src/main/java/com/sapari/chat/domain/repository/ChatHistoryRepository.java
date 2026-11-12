package com.sapari.chat.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * <p>커서 형식은 어댑터가 판정한다 — 형식이 어긋나면 {@code ChatHistoryQueryInvalidException}
     * (400)이다. 빈 목록으로 답하면 응답에 {@code hasNext} 가 없어 프론트가 길이로 다음 페이지를
     * 판단하는 계약 때문에 <b>스크롤이 사유 없이 조용히 끝나고</b>, 커서를 잘못 만든 버그가 "이력이
     * 더 없다" 와 구분되지 않는다. 형식 유효성이 응답으로 갈려도 새는 것은 없다 — 커서가 어떤
     * 모양인지는 공개된 사실이고 클라이언트가 서버에 묻지 않고도 스스로 판정한다.
     *
     * <p>판정이 호출자가 아니라 여기 있는 이유: 커서의 모양은 <b>저장소의 지식</b>이다. 응용 서비스가
     * 그걸 알면 저장소를 바꿀 때 서비스가 따라 바뀌고, 소비자가 늘면 같은 검사가 복제된다.
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

    /**
     * 그 방의 가장 최근 공지(NOTICE) 한 건. 없으면 빈 값이다.
     *
     * <p>제외 대상은 위 두 조회와 같다 — 이력에서 빠지는 강퇴자의 공지가 여기서만 보이면 같은 방의
     * 두 조회가 다른 답을 한다.
     */
    Optional<ChatMessage> findLatestNotice(UUID roomId, Collection<UUID> excludedSenderIds);
}
