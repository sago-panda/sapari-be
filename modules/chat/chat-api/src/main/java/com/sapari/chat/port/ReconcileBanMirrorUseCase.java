package com.sapari.chat.port;

import com.sapari.chat.command.ReconcileBanMirrorCommand;

@BlockingChat
public interface ReconcileBanMirrorUseCase {

    /**
     * 지금 유효한 밴 전부를 정본에서 미러로 다시 깐다. blocking — live-app(MVC)에서 호출.
     *
     * <p>판정이 미러 키의 존재 하나라 <b>키를 잃으면 정본의 유효한 밴이 집행되지 않고</b>, 그 부재는
     * 정상 false 로 읽혀 fail-open 지표에도 안 잡힌다. 그 상태를 되돌리는 수단이 이것이다. 그동안 열린
     * 세션도 끊는다 — 미러에 쓴 밴마다 계정 밴 이벤트를 낸다.
     *
     * <p>과다 차단(정본에 없는데 미러에 남은 키)은 여기서 고치지 않는다 — 그 사용자를 정본에서
     * 열거할 수 없다. 그쪽 수단은 밴 해제({@link ReleaseBanUseCase})이고, 해제는 밴이 없어도 미러를 지운다.
     */
    ReconcileOutcome reconcile(ReconcileBanMirrorCommand command);

    /**
     * 재적재의 결과.
     *
     * @param active   정본에서 읽은 유효한 밴 수
     * @param restored 그중 <b>미러에 없어서 새로 쓴</b> 수 — 정본과 미러가 어긋나 있던 사용자 수다.
     *                 0 이 아니면 그동안 그만큼의 밴이 집행되지 않았다
     */
    record ReconcileOutcome(int active, int restored) {
    }
}
