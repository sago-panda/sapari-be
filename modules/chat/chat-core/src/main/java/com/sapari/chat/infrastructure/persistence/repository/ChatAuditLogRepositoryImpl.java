package com.sapari.chat.infrastructure.persistence.repository;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatAuditLogRepository} 구현 — 도메인 기록을 네이티브 INSERT 한 번으로 옮긴다.
 *
 * <p>트랜잭션을 열지 않는다. 쓰기 쿼리라 호출자가 연 트랜잭션 안에서 돌아야 하고, 그 경계가
 * 어디서 닫히는지가 이 기록의 뜻을 정한다({@link ChatAuditEntry} javadoc) — 조치와 같은
 * 트랜잭션이면 둘이 갈리지 않고, 아니면 "조치가 성공한 뒤" 가 의미가 된다.
 *
 * <p><b>{@code ObjectMapper} 를 직접 만든다.</b> 형제 어댑터 다섯이 전부 그렇다(실측: chat 의
 * 직렬화 어댑터 5/5, 주입 0건) — {@code ChatKickEventRedisPublisher} 가 그 근거를 적어 두었고
 * 여기도 같다: {@code detail} 은 {@code Map<String, String>} 이라 시간 모듈도 MixIn 도 필요 없다.
 * 앱의 빈을 받으면 <b>저장되는 모양이 웹 직렬화 설정에 묶이고</b>, 그 설정을 바꾸는 사람은 자기가
 * 감사 기록의 형식을 바꾸는 줄 모른다. 덤으로 이 어댑터가 관계형 스택만 요구하게 된다 —
 * 받게 두면 Jackson 빈이 없는 컨텍스트에서 조립이 깨진다(실측: 주입으로 짰을 때
 * live-app 의 컨텍스트 테스트 12건이 {@code NoSuchBeanDefinitionException: ObjectMapper} 로 떴다).
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core 는 리액티브 앱과 블로킹 앱이 함께 의존하는데,
 * 스캔되는 순간 양쪽에 빈이 만들어지려 한다 — 관계형 DB 가 없는 streaming-app 에서는 그 시도가 곧
 * 부팅 실패다. 블로킹 어댑터는 그 스택을 실제로 가진 앱이 {@code @Bean} 으로 등록한다(형제 셋과 같다).
 */
@RequiredArgsConstructor
public class ChatAuditLogRepositoryImpl implements ChatAuditLogRepository {

    private final ChatAuditLogJpaRepository jpaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void append(ChatAuditEntry entry) {
        jpaRepository.insert(
                entry.actorId(),
                entry.actorRole().name(),
                entry.action().name(),
                entry.targetId(),
                entry.targetType().name(),
                serialize(entry.detail()),
                entry.occurredAt());
    }

    /**
     * {@code detail} 을 jsonb 문자열로. <b>실패는 전파한다</b> — 형제
     * {@code ChatKickEventRedisPublisher.serialize} 와 같은 판단이다. 여기서 삼키면 감사 행이
     * {@code detail} 없이 남는데, 그 행이 답해야 하는 유일한 질문이 "무엇을" 이라서
     * (사전 제거는 정본 행이 사라진다) 빈 {@code detail} 은 기록이 아니라 거짓 안심이 된다.
     *
     * <p>{@code Map<String, String>} 이라 실제로는 도달하지 않는다. 도달했다면 그 계약이 바뀐 것이다.
     */
    private String serialize(Map<String, String> detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("감사 detail 직렬화 실패 — 값 타입 계약을 확인할 것", e);
        }
    }
}
