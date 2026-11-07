package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;

/**
 * 옛 윈도우 인덱스 드롭이 <b>무엇만</b> 삼키는가.
 *
 * <p>왜 이 칸이 필요한가. 그 판정의 분모가 0 이었다 — 판정을 {@code return true} 로 넓혀도 세 모듈
 * 1,503칸이 전부 초록이다(실측). 부팅 경로가 이미 걷어낸 환경에서만 이 분기를 밟으므로, 넓힌 판정이
 * 밟는 벡터는 전 스위트에 없다. 그런데 좁게 잡은 <b>근거</b>는 코드에 적혀 있다 — 넓히면 권한
 * 부족·연결 끊김까지 "이미 없다" 로 라벨이 갈리고, 그러면 쓰기 증폭이 남은 상태가 조용히 통과한다.
 * 그 문장을 재는 장치가 이 파일이다.
 *
 * <p>컨테이너를 쓰지 않는다. 재는 것이 <b>예외 사슬의 판정</b> 하나라 Mongo 가 필요 없고,
 * 선례도 같다({@code MongoOperationTimeout} 의 칸).
 */
@DisplayName("인덱스 드롭이 삼키는 것 — IndexNotFound 하나")
class IndexNotFoundPredicateTest {

    private static MongoCommandException commandFailure(int code, String codeName) {
        BsonDocument response = new BsonDocument()
                .append("ok", new BsonInt32(0))
                .append("code", new BsonInt32(code))
                .append("codeName", new BsonString(codeName))
                .append("errmsg", new BsonString("probe"));
        return new MongoCommandException(response, new ServerAddress());
    }

    @Test
    @DisplayName("⭐ IndexNotFound(27)만 참이다 — 그 인덱스가 이미 걷혀 있는 멱등 경로다")
    void onlyIndexNotFoundIsSwallowed() {
        assertThat(ChatMongoConfig.isIndexNotFound(commandFailure(27, "IndexNotFound")))
                .as("이미 걷어낸 환경에서 부팅이 실패한다")
                .isTrue();
    }

    @Test
    @DisplayName("⭐ 권한 부족(13)은 삼키지 않는다 — 삼키면 쓰기 증폭이 남은 상태가 조용히 통과한다")
    void anAuthorizationFailureIsNotSwallowed() {
        assertThat(ChatMongoConfig.isIndexNotFound(commandFailure(13, "Unauthorized")))
                .as("권한 부족이 '이미 없다' 로 라벨이 갈렸다 — 드롭이 안 됐는데 부팅은 성공한다")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ Spring 이 감싼 사슬 안쪽도 찾는다 — 어댑터가 드라이버 예외를 자기 계층으로 감싼다")
    void theWrappedChainIsWalked() {
        assertThat(ChatMongoConfig.isIndexNotFound(new DataAccessResourceFailureException(
                "wrapped", commandFailure(27, "IndexNotFound"))))
                .as("감싸여 오면 못 알아본다 — 그러면 멱등 경로에서 부팅이 실패한다")
                .isTrue();
    }

    @Test
    @DisplayName("자기참조 사슬에서 멈춘다 — 안 멈추면 부팅이 무한 루프다")
    void aSelfReferencingChainTerminates() {
        // 드라이버가 만들 모양은 아니지만, 사슬을 걸어 내려가는 코드는 그 가드 없이는 멈추지 않는다.
        // 이 칸이 재는 것은 "판정이 false 를 돌려준다" 가 아니라 "돌려준다" 자체다.
        RuntimeException selfReferencing = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ChatMongoConfig.isIndexNotFound(selfReferencing)).isFalse();
    }
}
