package com.sapari.chat.infrastructure.config;

import java.util.concurrent.atomic.AtomicReference;

import com.sapari.chat.application.port.ProfanityFilterSource;
import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 필터 참조를 원자로 갈아 끼우는 셀 — {@link ProfanityFilterSource} 의 유일한 구현.
 *
 * <p><b>독자는 블록되지 않는다.</b> 읽기가 단일 참조 읽기이고 쓰기가 단일 참조 쓰기라 락이 없다.
 * 교체 중에 읽는 메시지는 이전 필터나 다음 필터 중 하나를 집어 들고 그것으로 끝까지 간다 —
 * 두 사전이 섞인 상태를 보는 경로가 없다. 필터가 불변이라 성립하는 성질이고, 그래서 교체는
 * 필터를 고치는 것이 아니라 <b>새로 만들어 바꿔 끼우는</b> 것이다.
 *
 * <p><b>왜 {@code AtomicReference} 인가.</b> 가시성만 보면 {@code volatile} 필드로도 같고, 이
 * 저장소에 선례가 양쪽 다 있다({@code ActiveRoomGauge} 가 {@code AtomicReference},
 * {@code ChatSessionRegistry}·{@code OutboundFrame} 이 {@code volatile}). 그래서 컨벤션으로는
 * 어느 쪽도 위반이 아니다.
 *
 * <p>고른 근거는 <b>곧 필요해지는 연산</b>이다. 사전 전파가 "델타 + 단조 버전" 으로 가므로
 * (버전이 {@code local+1} 이면 적용, 어긋나면 전체 재읽기) 그 단조성을 지키려면 교체가 "지금 값이
 * 내가 읽은 그것일 때만" 이어야 하고, 그건 {@code compareAndSet}/{@code updateAndGet} 이다 —
 * {@code volatile} 로는 쓸 수 없다. 즉 지금은 과해 보이지만 그때 load-bearing 이 된다.
 *
 * <p>(처음에 적어 둔 근거는 "타입이 선언을 들고 있게" 였다. 그것도 참이지만 재현되는 근거가
 * 아니라 취향이고, 취향으로 적어 두면 다음 사람이 바꿀 때 무엇을 잃는지 모른다.)
 *
 * <p><b>핫패스 비용</b>(2026-11-05 실측, 1,078 항목 사전 · 메시지 1건 · 각 20만 회 5라운드의
 * 중간값, 두 경로를 번갈아 워밍업해 JIT 상태를 맞춤):
 * <pre>
 *   직접 호출      19.683 µs/건
 *   이 셀 경유     19.705 µs/건
 *   차이          0.022 µs/건  (0.11%)
 * </pre>
 * 사전 변경을 배포로만 할 수 있던 대가와 비교할 값이 아니다. 수를 적어 두는 이유는 "무료" 라고
 * 적으면 다음 사람이 그 자리에 더 무거운 구현(조회·검증)을 넣어도 같은 문장이 남기 때문이다 —
 * 이 수는 <b>참조 하나를 읽는 구현</b>에 대한 것이다.
 *
 * <p><b>트라이 재빌드는 이 클래스가 하지 않는다.</b> 새 필터를 만드는 것은 호출자이고 그 비용은
 * p50 0.54 ms · max 1.45 ms(1,078 항목)다 — 항목 수에 선형이라, 호출자가 이벤트 루프에서 떼야 한다.
 * 여기서 만들면 그 결정이 이 클래스 안에 숨는다.
 *
 * <p>스테레오타입을 붙이지 않는다. 이 스택을 소유한 앱이 {@code @Bean} 으로 등록한다 — 설정
 * 프로퍼티와 사전 적재가 그 앱의 결정이기 때문이고, streaming-app 이 {@code com.sapari.chat} 을
 * 스캔하므로 붙이면 거기서 두 번 생긴다.
 */
public final class SwappableProfanityFilter implements ProfanityFilterSource {

    private final AtomicReference<ProfanityFilter> current;

    public SwappableProfanityFilter(ProfanityFilter initial) {
        if (initial == null) {
            // null 로 시작하면 첫 메시지에서 마스킹이 조용히 꺼진다 — 이 도메인이 부팅 실패를
            // 택해 막아 둔 상태라, 여기서도 그 결정을 지킨다.
            throw new IllegalArgumentException("초기 필터는 필수입니다 — null 이면 마스킹이 조용히 꺼집니다.");
        }
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public ProfanityFilter current() {
        return current.get();
    }

    /**
     * 필터를 갈아 끼운다. 다음 메시지부터 새 사전이 쓰인다.
     *
     * <p>이미 처리 중인 메시지는 자기가 집어 든 필터로 끝까지 간다 — 되돌아가 다시 마스킹하지
     * 않는다. 그리고 <b>이미 저장된 메시지는 소급해서 바뀌지 않는다</b>: 영속 문서가
     * {@code originalMessage} 와 {@code displayMessage} 를 둘 다 들고 있고 이력 조회는 저장된
     * 값을 읽으므로, 사전을 고쳐도 지난 메시지의 마스킹은 그대로다. 운영자가 기대할 수 있는
     * 지점이라 적어 둔다.
     */
    public void swap(ProfanityFilter next) {
        if (next == null) {
            throw new IllegalArgumentException("교체할 필터는 필수입니다 — null 이면 마스킹이 꺼집니다.");
        }
        current.set(next);
    }
}
