package com.sapari.chat.port;

import com.sapari.chat.command.ReleaseBanCommand;

@BlockingChat
public interface ReleaseBanUseCase {

    /**
     * 플랫폼 밴을 푼다 — 정본 행 삭제 + 사면 기준선 + 미러 삭제. blocking — live-app(MVC)에서 호출.
     *
     * <p><b>멱등이다 — 다만 한 축에 범위가 있다.</b> 사면 기준선이 <i>요청 처리 시각</i>이라,
     * 같은 요청을 한참 뒤에 다시 보내면 그 사이에 쌓인 강퇴까지 접힌다(T0 해제 → 새 강퇴 3건 →
     * T0 요청 재전송 → 그 3건도 접힘). {@code MAX} 가 시계 역행은 막으므로 기준선이 과거로
     * 되돌아가지는 않는다. 운영자 의도("이 사람의 누적을 접는다")와 크게 어긋나지 않아 그대로
     * 두지만, "몇 번을 불러도 같은 종단 상태" 라는 뜻으로는 읽지 말 것.
     *
     * <p>풀 밴이 없어도 성공으로 끝난다. 그 성질에 기대는 곳이 있다 — 정본은
     * 지워졌는데 미러 삭제가 실패한 뒤의 재시도다. 거기서 "풀 밴이 없다" 로 거부하면 미러에 도달할
     * 코드가 영영 안 남고, 영구 밴이면 사람이 Redis를 직접 만지기 전까지 풀리지 않는다.
     */
    ReleaseBanResult release(ReleaseBanCommand command);

    /**
     * 무엇을 풀었는가.
     *
     * <p><b>{@code banExisted} 를 돌려주는 것이 안전장치다.</b> 응답이 존재 여부를 누설하지 않는
     * 쪽이 맞지만, 그렇다고 <b>누른 사람에게도 신호가 0</b> 이면 안 된다 — 실존하는 다른 사용자
     * UUID 를 한 글자 틀리면 그 사람의 730일 누적이 조용히 접히고, 감사 테이블이 append-only 라
     * 앱 경로로 기준선을 낮출 수도 없다. 이 값이 그 자리에서 "풀 밴이 없었다" 를 보여 준다.
     *
     * @param banExisted 실제로 밴이 있었는가
     * @param wasPermanent 지운 밴이 영구였는가. 밴이 없었으면 거짓이다 — 영구 밴은 수동 밴 경로가
     *                     0건이라 <b>앱으로 다시 걸 수 없다</b>
     */
    record ReleaseBanResult(boolean banExisted, boolean wasPermanent) {
    }
}
