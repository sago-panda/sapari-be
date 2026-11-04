package com.sapari.chat.infrastructure.redis;

/**
 * 키가 기대한 타입이 아니라서 난 실패인지 가려낸다 — 강퇴 명단을 읽는 쪽과 쓰는 쪽이 같은 판별을 쓴다.
 *
 * <p><b>예외 타입으로는 갈라낼 수 없어 메시지로 판별한다.</b> 실측하면 {@code SISMEMBER}는
 * {@code RedisSystemException("Error in execution")}으로 감싸여 오고 그 원인이
 * {@code RedisCommandExecutionException}인데, 이 타입은 서버가 에러로 답한 모든 경우(OOM·READONLY 등)에
 * 똑같이 쓰이므로 타입만 봐서는 일시 장애와 구분되지 않는다. 구분해 주는 건 서버가 그대로 실어 보낸
 * 메시지의 첫 낱말뿐이라, 여기에 타입 검사를 더해도 좁혀지는 것 없이 드라이버 클래스에 묶이기만 한다.
 *
 * <p>Lua로 실행해도 같다 — 오염된 키에 스크립트를 돌리면 응답이
 * {@code "WRONGTYPE Operation against a key holding the wrong kind of value script: ..."}로 와서
 * 같은 판별이 그대로 걸린다(실측).
 */
final class RedisWrongType {

    /** Redis가 타입 불일치에 돌려주는 에러 응답의 첫 낱말. */
    private static final String WRONG_TYPE = "WRONGTYPE";

    private RedisWrongType() {
    }

    /** 감싸인 예외라 원인 사슬을 따라 내려가며 본다. cause가 자기 자신인 경우를 대비해 끊는다. */
    static boolean matches(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.startsWith(WRONG_TYPE)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
