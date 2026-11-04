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
 * <p><b>읽기·검사까지 같은 트랜잭션에 넣지 않았다.</b> ⚠️ 한때 그 근거를 "동시 쓰기 둘은 각자
 * 자기 항목이 든 결과 집합을 검사하므로 어기는 항목을 가진 쪽이 자기 검사에서 거부된다" 로
 * 적었다. <b>규칙 (1)에 대해 거짓이다</b> — 그 규칙은 <i>두 kind 를 가로지른다</i>
 * ({@code ProfanityDictionary.rejectUselessWhitelist} 가 화이트 항목을 <b>단어 집합</b>에 대고
 * 본다). 그래서 A 가 화이트리스트에 {@code 시발점} 을, B 가 같은 시각 욕설에 {@code 시발점} 을
 * 넣으면 <b>둘 다 자기 결과에서는 통과</b>하고 커밋된 합집합만 어긴다. 그 뒤 발행은 전 Pod 가
 * 거부하고 이후 모든 추가가 409 로 막힌다(복구는 둘 중 하나 제거).
 *
 * <p>락을 두지 않는 쪽을 유지한다. 그 조합은 <b>같은 문자열을 서로 다른 kind 에 동시에</b> 넣어야
 * 성립하고(관리자 쓰기는 사람 타이핑 빈도다) 결과가 복구 가능하며, 닫는 비용은 읽기·검사·쓰기를
 * 한 트랜잭션에 묶고 그 안에서 Redis 를 치는 것이다 — 이 저장소가 피하는 모양이다. 대신
 * <b>조용하지 않게</b> 한다: 쓰기 뒤 커밋된 집합을 다시 문에 통과시켜 어기면 그 자리에서 ERROR 로
 * 남긴다({@code ManageFilterWordsService.warnIfCommittedSourceViolates}). 그러면 누른 사람의
 * 요청이 만든 상태가 그 요청의 로그에 붙는다.
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
