package com.sapari.chat.domain.repository;

import java.util.Optional;

import com.sapari.chat.domain.model.ChatMessageEvidence;

/**
 * 강퇴 증거로 삼을 메시지를 서버가 직접 읽는 포트. <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p><b>{@link ChatMessageRepository}와 왜 따로 있나</b>: 그쪽은 {@code Mono}/{@code Flux}를 반환하는
 * 리액티브 포트라 MVC에서 쓰면 결국 {@code block()}을 강요당한다. 스택이 다르면 포트도 다르다는 것이
 * 이 모듈의 규칙이고, ArchUnit이 두 앱의 오용을 막는 것도 같은 이유다.
 *
 * <p>읽어 오는 것은 {@link ChatMessageEvidence} — 메시지 전체가 아니다. 이 경로는 발신자 이메일을 쓰지
 * 않으므로 가져오지 않는다.
 *
 * <p><b>없으면 빈 값이다.</b> id 형식이 저장소가 아는 모양이 아닌 경우도 여기 포함된다 — 호출자에게
 * "형식이 틀렸다"와 "그런 메시지가 없다"는 같은 결론(강퇴 거부)으로 이어지고, 둘을 갈라 응답하면
 * 메시지 id를 더듬어 찾는 통로가 된다.
 */
public interface ChatMessageEvidenceRepository {

    Optional<ChatMessageEvidence> findEvidence(String messageId);
}
