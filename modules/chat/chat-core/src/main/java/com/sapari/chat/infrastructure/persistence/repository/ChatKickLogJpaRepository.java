package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatKickLogEntity;

public interface ChatKickLogJpaRepository extends JpaRepository<ChatKickLogEntity, UUID> {

    /**
     * 강퇴를 기록하되 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>왜 네이티브인가</b>: 중복 강퇴는 오류가 아니라 무동작이어야 한다. JPA {@code persist}는 유니크
     * 위반을 예외로 던지므로 호출자가 "이미 있음"과 "쓰기 실패"를 구분할 수 없고, 그 구분이 밴 카운트를
     * 올릴지 말지를 정한다. {@code ON CONFLICT DO NOTHING}은 그 구분을 반환값 하나로 준다.
     *
     * <p><b>왜 {@code RETURNING id}가 아닌가</b>: 설계 문서는 {@code RETURNING id}로 적혀 있지만, 그 목적은
     * "실제로 새 행이 생겼는가"를 가르는 것이고 생성된 id를 쓰는 곳은 하류에 없다. 영향 행 수가 같은 답을
     * 더 적은 배관으로 준다 — 충돌이면 0, 삽입이면 1이다.
     *
     * <p>{@code id}는 넘기지 않는다(테이블 기본값이 생성한다). 반면 {@code kicked_at}은 기본값에 맡기지 않고
     * 명시로 넣는다 — 시각은 주입된 시계에서 와야 하고(테이블 기본값은 DB 시계다), 누적 강퇴 2년 창을
     * 고정 시계로 검증할 수 있어야 한다.
     *
     * @return 영향 행 수 — 1이면 실제 기록, 0이면 이미 있어 건너뜀
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.chat_kick_log
                   (user_id, live_room_id, kicked_by_id, kicked_by_role, triggering_message, kicked_at)
            VALUES (:userId, :roomId, :kickedById, :kickedByRole, :triggeringMessage, :kickedAt)
            ON CONFLICT (user_id, live_room_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("roomId") UUID roomId,
                       @Param("kickedById") UUID kickedById,
                       @Param("kickedByRole") String kickedByRole,
                       @Param("triggeringMessage") String triggeringMessage,
                       @Param("kickedAt") Instant kickedAt);

    /**
     * {@code since} 이후 이 사용자를 강퇴한 <b>서로 다른 사람의 수</b> — 강퇴 횟수가 아니다.
     *
     * <p><b>왜 행이 아니라 사람인가.</b> 유니크 제약이 {@code (user_id, live_room_id)}라 행은 방 단위로
     * 쌓인다. 행을 세면 <b>한 판매자가 혼자 임계에 닿는다</b> — 방송을 세 번 하고 매번 같은 사람을 한 번씩
     * 강퇴하면 3행이다. 방송 3회는 공모가 아니라 평범한 업무이고, 그러면 판매자 하나가 자기 방에서
     * 채팅한 누구에게든 플랫폼 전역 밴을 걸 수 있다.
     *
     * <p>사람을 세면 임계가 <b>확증</b>을 요구한다. 정확히는 "독립된 운영자 셋"이 아니라
     * <b>"서로 다른 방 셋에서, 각 방의 첫 강퇴자가 서로 다르다"</b>이다 — 한 방에서는
     * {@code ON CONFLICT DO NOTHING} 때문에 첫 강퇴만 행이 되므로, 같은 방의 두 번째 판단은 다른 사람이
     * 내려도 세어지지 않는다. 과소 집행 방향이라 받아들인다.
     *
     * <p><b>이건 정본을 다시 읽은 것이 아니라 정본에서 벗어난 것이다.</b> 설계 문서의 공식은
     * {@code COUNT(chat_kick_log WHERE ...)}로 {@code DISTINCT} 없이 적혀 있고, 원래 구현이 그것을 정확히
     * 따랐다. 프로즈의 "전 판매자 합산"은 <b>범위</b>를 말하지 단위를 말하지 않는다. 즉 문서 안에서
     * <b>공식이 의도를 배반하고 있었고</b>, 의도 쪽으로 고쳤다. 이 구분을 적어 두는 이유는 "문서대로다"로
     * 적으면 다음 사람이 그 공식을 보고 되돌리기 때문이다.
     *
     * <p>반대 방향은 열려 있다 — 한 판매자가 자기 방송 열 번에서 열 번 강퇴해도 1이라 밴이 안 걸린다.
     * 그리고 그 위에 아무것도 없다(판매자 단위 밴 없음, 수동 밴 경로 없음). 이건 이 선택의 대가다.
     *
     * <p>방 조건은 여전히 넣지 않는다 — 밴은 판매자별이 아니라 플랫폼 단위라, 방을 옮겨 다녀도 같은
     * 카운터에 쌓여야 한다.
     *
     * <p><b>인덱스는 쓰이되 한 단계 내려간다.</b> {@code (user_id, kicked_at DESC)}에
     * {@code kicked_by_id}가 없어 Index Only Scan이 못 되고 매칭 행마다 힙을 봐야 한다. 얼마나 비싼지는
     * <b>선택도에 달렸고 계획도 안정적이지 않다</b> — 리뷰 실측에서 plain Index Scan과 Bitmap Heap Scan이
     * 데이터 모양에 따라 갈렸고, 후자는 힙 페이지 수로 상한이 걸린다. 그래서 여기 숫자를 못 박지 않는다.
     *
     * <p>지금 고치지 않는 근거는 성능 수치가 아니라 <b>행 수가 구조적으로 유계</b>라는 것이다: 활성 밴이
     * 있으면 이 쿼리에 도달조차 하지 않고, 밴 걸린 사용자는 게이트에 막혀 더 강퇴당하지 못한다.
     *
     * <p>그 전제가 깨지면 {@code INCLUDE (kicked_by_id)}가 힙 접근을 없앤다. <b>공짜가 아니다</b> —
     * 리프 튜플에 uuid 16바이트가 붙는 만큼 <b>인덱스가 약 45% 커진다</b>(실측, 여러 데이터 모양에서
     * 42~45%). 한때 이 자리에 "인덱스 크기는 오히려 감소"라고 적혀 있었는데 사실이 아니었다.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT kicked_by_id) FROM live_schema.chat_kick_log
             WHERE user_id = :userId AND kicked_at > :since
            """, nativeQuery = true)
    long countDistinctKickersSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
