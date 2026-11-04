package com.sapari.chat.application.service;

import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;

/**
 * 사전 정본의 한 줄 쓰기를 트랜잭션으로 감싼다.
 *
 * <p><b>왜 별도 빈인가.</b> 쓰기가 {@code @Modifying} 네이티브 쿼리라 트랜잭션 없이는
 * {@code TransactionRequiredException} 이다. 그리고 {@code @Transactional} 이 프록시로 걸리려면
 * 호출이 다른 빈에서 와야 한다 — 같은 클래스 안의 메서드로 두면 자기 호출이라 프록시를 타지
 * 않고, 붙여 놓고 안 걸리는 상태가 된다({@code ChatKickRecorder} 와 같은 이유).
 *
 * <p><b>읽기·검사까지 같은 트랜잭션에 넣지 않았다.</b> 넣으면 선검사와 쓰기 사이의 창이 닫히지만
 * 그 창으로 들어올 수 있는 것이 없다 — 규칙을 어기는 행이 생기는 길은 둘(정본 직접 수정 · 규칙
 * 조이기)이고 둘 다 이 엔드포인트와 동시에 일어나지 않는다. 동시 쓰기 둘은 각자 <b>자기 항목이
 * 든 결과 집합</b>을 검사하므로, 어기는 항목을 가진 쪽이 자기 검사에서 거부된다.
 *
 * <p><b>발행은 이 경계 밖이다.</b> 안에서 발행하면 롤백된 단어가 함대에 깔리고, 그 상태는 정본에
 * 흔적이 없어 어디서 왔는지 찾을 수 없다.
 */
@RequiredArgsConstructor
public class ChatFilterWordWriteRecorder {

    private final ChatFilterWordRepository repository;

    /** @return 실제로 새로 들어갔는가. {@code false} 는 이미 있었다는 뜻이고 오류가 아니다 */
    @Transactional
    public boolean add(FilterWordKind kind, String word, UUID actorId) {
        return repository.add(kind, word, actorId);
    }

    /** @return 실제로 지워졌는가. {@code false} 는 없었다는 뜻이고 오류가 아니다 */
    @Transactional
    public boolean remove(FilterWordKind kind, String word) {
        return repository.remove(kind, word);
    }
}
