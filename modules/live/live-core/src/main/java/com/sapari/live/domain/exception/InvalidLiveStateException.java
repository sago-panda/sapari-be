package com.sapari.live.domain.exception;

/**
 * 지금 방 상태에서 할 수 없는 요청. 코드는 요청 종류별로 고른다(시작 LIVE-003, 종료·만료 LIVE-009,
 * 입장 LIVE-010, 송출 키 발급 LIVE-011).
 *
 * <p>클래스를 코드별로 나누지 않는다 — 재조정 배치가 이 타입을 잡아 "경합에서 짐 → 건너뜀"으로
 * 읽는다({@code ReconcileExpiredReadyService}·{@code ReconcileStaleLiveService}). 나누면 그 catch 가
 * 새 타입을 놓친다.
 *
 * <p>코드 없는 생성자를 두지 않는다 — 기본값이 있으면 새 요청 종류가 "시작할 수 없는 상태" 문구를
 * 조용히 물려받는다.
 */
public class InvalidLiveStateException extends LiveDomainException{

    public InvalidLiveStateException(LiveErrorCode errorCode, String message){
        super(errorCode, message);
    }
}
