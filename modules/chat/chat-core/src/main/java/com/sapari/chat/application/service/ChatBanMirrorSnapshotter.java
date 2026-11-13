package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.VersionedBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;

import lombok.RequiredArgsConstructor;

/**
 * 한 사용자의 유효한 밴과 미러 변경 번호를 <b>밴 판단 잠금 안에서</b> 함께 읽는 얇은 빈.
 *
 * <p>재적재도 미러에 쓰므로 강퇴·해제와 같은 번호 규칙을 따라야 한다. 잠금 없이 읽고 번호를 집으면
 * "재적재가 밴을 읽음 → 해제가 커밋하고 미러를 지움 → 재적재가 더 큰 번호로 씀" 이 열려, 방금 푼 밴이
 * 되살아난다. 잠금 안에서 둘을 함께 하면 해제는 이 읽기의 앞이거나(그러면 밴이 안 읽힌다) 뒤다(그러면
 * 해제의 번호가 더 커서 이 쓰기가 거절된다).
 *
 * <p>별도 빈인 이유는 {@link ChatKickRecorder}와 같다 — 자기 호출은 프록시를 타지 않는다. 스테레오타입을
 * 달지 않는다. 이 스택을 소유한 앱이 {@code @Bean}으로 등록한다.
 */
@RequiredArgsConstructor
public class ChatBanMirrorSnapshotter {

    private final ChatBanStateRepository banStateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            timeout = ChatKickRecorder.TRANSACTION_TIMEOUT_SECONDS)
    public Optional<VersionedBan> snapshot(UUID userId, Instant now) {
        banStateRepository.lockForBanDecision(userId);
        return banStateRepository.findActive(userId, now)
                .map(ban -> new VersionedBan(ban, banStateRepository.nextChangeVersion()));
    }
}
