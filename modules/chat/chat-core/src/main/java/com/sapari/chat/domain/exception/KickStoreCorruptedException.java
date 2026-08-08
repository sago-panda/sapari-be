package com.sapari.chat.domain.exception;

/**
 * 강퇴 명단 키가 우리 것이 아닌 타입으로 존재해 읽지도 쓰지도 못하는 상태.
 *
 * <p><b>일시 장애와 분리하려고 존재하는 타입이다.</b> 커넥션 끊김·타임아웃은 다음 요청에 낫지만 이건 낫지
 * 않는다 — 재시도는 멱등이면서 <b>영원히</b> 실패한다. 같은 예외로 뭉뚱그리면 "재시도가 알아서 복구한다"는
 * 이 코드베이스의 전제가 이 한 경우에만 조용히 거짓이 되고, 그 사실이 로그에서 드러나지 않는다.
 *
 * <p>사용자 잘못이 아니라 서버 쪽 상태의 문제라 5xx({@link ChatErrorCode#KICK_STORE_CORRUPTED})다.
 * 조회 경로는 이 예외를 fail-open으로 흡수해 클라이언트까지 가지 않고, 등록 경로에서 밖으로 나가면
 * 핸들러가 5xx로 응답하며 스택과 함께 남긴다. <b>치워야 할 키 이름은 로그에만 실린다</b> —
 * 응답 문구는 에러코드가 갖고 있어 내부 식별자가 밖으로 새지 않는다.
 *
 * <p><b>서버는 이 키를 지우지 않는다.</b> 지우면 그 방에 이미 쌓인 강퇴 명단이 통째로 사라져 강퇴됐던
 * 사람이 전원 돌아오고, 이 키를 쓰는 쪽이 따로 있다면 그쪽 데이터를 파괴하는 것이기도 하다. 정본
 * ({@code chat_kick_log})에서 다시 채우는 경로가 생기기 전까지 이 판단은 사람 몫이다.
 */
public class KickStoreCorruptedException extends ChatException {

    /** 사람이 손대야 할 대상. 로그에서 바로 집어낼 수 있도록 예외가 들고 다닌다. */
    private final String key;

    public KickStoreCorruptedException(String key, Throwable cause) {
        super(ChatErrorCode.KICK_STORE_CORRUPTED, "강퇴 명단 키의 타입이 어긋났다 — key=" + key, cause);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
