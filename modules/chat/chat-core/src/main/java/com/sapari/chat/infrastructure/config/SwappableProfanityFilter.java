package com.sapari.chat.infrastructure.config;

import java.util.concurrent.atomic.AtomicReference;

import com.sapari.chat.application.port.ProfanityFilterInstaller;
import com.sapari.chat.application.port.ProfanityFilterSource;
import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 필터 참조를 원자로 갈아 끼우는 셀 — {@link ProfanityFilterSource}(읽기)와
 * {@link ProfanityFilterInstaller}(쓰기)의 유일한 구현.
 *
 * <p>포트가 둘인 것이 요점이다. 읽는 쪽은 메시지마다 불리는 핫패스고 쓰는 쪽은 사전이 바뀔 때만
 * 불린다 — 한 포트로 합치면 전송 경로가 교체 메서드를 손에 들게 되고, 그건 그 경로가 가져야 할
 * 권한이 아니다. 같은 객체이고 역할이 둘이다.
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
 * <p>⚠️ <b>여기 적혀 있던 근거는 실현되지 않았다.</b> 한때 "사전 전파가 델타 + 단조 버전으로
 * 가므로 {@code compareAndSet} 이 곧 load-bearing 이 된다" 고 적었다. 전파를 실제로 붙일 때
 * (SPR-165) 둘 다 그렇게 되지 않았다:
 * <ul>
 *   <li><b>델타를 안 쓴다.</b> 트라이 재빌드가 1,078 항목에서 p50 0.54 ms 이고 사전 변경 빈도는
 *       사람이 타이핑하는 속도다 — 델타로 아끼는 것이 그 0.54 ms 이고, 대가가 적용 순서라는
 *       두 번째 상태다. 전체 스냅샷을 다시 읽는 경로 하나로 뒀다.
 *   <li><b>CAS 도 안 쓴다.</b> {@code ProfanityDictionaryRefreshHandler} 가 세 트리거(부팅·알림·
 *       주기)를 한 스트림으로 모아 {@code concatMap} 으로 흘리므로 <b>교체하는 스레드가 하나</b>다.
 *       단조 판정이 그 핸들러의 필드 비교 하나로 끝나고, 여기까지 내려올 때는 이미 "적용할 것" 으로
 *       정해져 있다.
 * </ul>
 *
 * <p>그래서 지금 이 필드에 필요한 성질은 <b>가시성뿐</b>이고 {@code volatile} 필드로도 같다.
 * 그대로 두는 이유는 바꿔서 얻는 것이 없기 때문이다 — {@code AtomicReference.get()} 이 곧
 * volatile 읽기라 아래 실측 수가 그 선택의 상한이다. 바꾸지 말라는 뜻은 아니고, <b>바꿔도
 * 잃는 것이 없다</b>는 뜻이다.
 *
 * <p>(그 전에 적어 둔 근거는 "타입이 선언을 들고 있게" 였다. 그것도 참이지만 재현되는 근거가
 * 아니라 취향이다. 취향을 근거 자리에 적으면 다음 사람이 바꿀 때 무엇을 잃는지 모르고, 그
 * 자리를 메우려고 적은 "곧 필요해진다" 가 이번에 거짓으로 판명됐다 — 아직 안 쓴 설계를 근거로
 * 적는 것이 그 실수의 모양이다.)
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
 * 이 수는 <b>참조 하나를 읽는 구현</b>에 대한 것이고, 그 참조를 {@code AtomicReference} 로
 * 들고 있는 형태에서 쟀다.
 *
 * <p><b>트라이 재빌드는 이 클래스가 하지 않는다.</b> 새 필터를 만드는 것은 호출자이고 그 비용은
 * p50 0.54 ms · max 1.45 ms(1,078 항목)다 — 항목 수에 선형이라, 호출자가 이벤트 루프에서 떼야 한다.
 * 여기서 만들면 그 결정이 이 클래스 안에 숨는다.
 *
 * <p>스테레오타입을 붙이지 않는다. 이 스택을 소유한 앱이 {@code @Bean} 으로 등록한다 — 설정
 * 프로퍼티와 사전 적재가 그 앱의 결정이기 때문이고, streaming-app 이 {@code com.sapari.chat} 을
 * 스캔하므로 붙이면 거기서 두 번 생긴다.
 */
public final class SwappableProfanityFilter
        implements ProfanityFilterSource, ProfanityFilterInstaller {

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
    @Override
    public void install(ProfanityFilter next) {
        if (next == null) {
            throw new IllegalArgumentException("교체할 필터는 필수입니다 — null 이면 마스킹이 꺼집니다.");
        }
        current.set(next);
    }
}
