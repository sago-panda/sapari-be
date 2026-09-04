package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.RoomSummary;
import com.sapari.live.application.port.SfuRoomResult;

/**
 * {@link LiveMediaManager} 호출 시간과 실패율을 재는 데코레이터. 서비스도 어댑터도 고치지 않는다.
 *
 * <p>이 계측이 필요한 이유는 <b>지연</b>이다. 시작 계열은 행 잠금을 쥔 채 LiveKit 을 부르므로
 * (모듈 AGENTS "Media ports" 참고) 이 구간의 지연은 그 방송 하나가 아니라 커넥션 풀 전체를 잡아먹는
 * 선행 지표다.
 *
 * <p><b>실패율은 예외를 던지는 메서드에서만 보인다 — 정리 계열은 여기서 영원히 {@code success} 다.</b>
 * {@code stopHlsEgress}·{@code deleteIngress}·{@code closeRoom} 은 실패를 삼키고 정상 반환하고
 * (그 자체는 의도된 설계다), 게다가 어댑터가 Retrofit 응답 코드를 검사하지 않아 <b>인증 실패조차
 * 정상 반환</b>이다. 데코레이터는 반환 여부만 보므로 그 실패가 여기 닿지 않는다.
 *
 * <p>그래서 {@code result="failure"} 가 0 인 것을 <b>"정리가 잘 되고 있다" 로 읽으면 안 된다.</b>
 * 정리 성공 여부는 정리 잡의 {@code _REQUESTED} 카운터가 회차마다 같은 수치를 반복하는지로 읽는다.
 * 어댑터가 응답을 검사하게 되면 그때 이 지표가 정리 실패율을 담는다(별도 티켓).
 *
 * <p><b>delegate 의 예외는 절대 삼키지 말 것.</b> 이 도메인의 실패 방향은 메서드마다 의도적으로 다르게
 * 설계돼 있다(전역 스윕은 던지고, 정리는 삼키고, 시작 랑데부는 빈 목록). 여기서 하나라도 잡아먹으면
 * "빈 목록 = 실패" 규약이 무너져 {@code ReconcileStaleLiveService} 의 공집합 가드가 무력화되고,
 * 그 결과는 멀쩡한 방송의 대량 종료다. 계측은 관찰만 한다.
 */
@Slf4j
public class MeteredLiveMediaManager implements LiveMediaManager {

    private final LiveMediaManager delegate;
    private final MeterRegistry registry;

    public MeteredLiveMediaManager(LiveMediaManager delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public SfuRoomResult createRoom(UUID roomId) {
        return timed("createRoom", () -> delegate.createRoom(roomId));
    }

    @Override
    public String issueSellerToken(UUID roomId, UUID sellerId) {
        return timed("issueSellerToken", () -> delegate.issueSellerToken(roomId, sellerId));
    }

    @Override
    public IngressResult createIngress(UUID roomId, UUID sellerId) {
        return timed("createIngress", () -> delegate.createIngress(roomId, sellerId));
    }

    @Override
    public List<String> publishingIngressIdsOrEmpty(UUID roomId) {
        return timed("publishingIngressIdsOrEmpty", () -> delegate.publishingIngressIdsOrEmpty(roomId));
    }

    @Override
    public List<IngressSummary> listRoomIngress(UUID roomId) {
        return timed("listRoomIngress", () -> delegate.listRoomIngress(roomId));
    }

    @Override
    public HlsEgressResult startHlsEgress(UUID roomId) {
        return timed("startHlsEgress", () -> delegate.startHlsEgress(roomId));
    }

    @Override
    public void stopHlsEgress(UUID roomId) {
        timed("stopHlsEgress", () -> {
            delegate.stopHlsEgress(roomId);
            return null;
        });
    }

    @Override
    public void deleteIngress(UUID roomId) {
        timed("deleteIngressByRoom", () -> {
            delegate.deleteIngress(roomId);
            return null;
        });
    }

    @Override
    public void deleteIngress(UUID roomId, String ingressId) {
        timed("deleteIngress", () -> {
            delegate.deleteIngress(roomId, ingressId);
            return null;
        });
    }

    @Override
    public void closeRoom(String sfuRoomId) {
        timed("closeRoom", () -> {
            delegate.closeRoom(sfuRoomId);
            return null;
        });
    }

    /** 설정에서 읽는 값이라 외부 호출이 아니다 — 계측하면 시계열만 늘고 알려주는 게 없다. */
    @Override
    public String getSfuUrl() {
        return delegate.getSfuUrl();
    }

    @Override
    public List<IngressSummary> listAllIngress() {
        return timed("listAllIngress", delegate::listAllIngress);
    }

    @Override
    public List<EgressSummary> listAllEgress() {
        return timed("listAllEgress", delegate::listAllEgress);
    }

    @Override
    public List<RoomSummary> listAllRooms() {
        return timed("listAllRooms", delegate::listAllRooms);
    }

    /**
     * 호출을 재고 결과 태그를 붙인다. 예외는 전부 {@code failure} 로 세고 <b>손대지 않는다</b>.
     *
     * <p><b>예외를 잡지 않는다</b> — 정상 반환에만 표시를 남기고, 그 표시가 없으면 실패로 본다.
     * {@code catch (RuntimeException)} 이면 {@code Error} 로 끝난 호출이 {@code success} 로 기록돼
     * 실패율이 오염되고, 그렇다고 {@code Throwable} 을 잡으면 죽어가는 JVM 을 건드리게 된다.
     * 표시 방식은 둘 다 피한다 — 어떤 이유로든 정상 반환이 아니면 실패다.
     *
     * <p>{@code Timer#record(Supplier)} 를 쓰지 않고 직접 쓴 이유: 그 오버로드는 예외가 나도
     * 성공/실패를 구분하지 않아, 정확히 여기서 알고 싶은 것(실패율)이 사라진다.
     */
    private <T> T timed(String op, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        boolean returnedNormally = false;
        try {
            T result = call.get();
            returnedNormally = true;
            return result;
        } finally {
            record(op, returnedNormally ? "success" : "failure", sample);
        }
    }

    /**
     * 계측 기록. <b>여기서 나는 예외는 삼킨다</b> — 클래스 주석의 "삼키지 말 것" 과 모순처럼 보이지만
     * 대상이 다르다. 그 규칙이 지키는 건 <b>delegate 가 던진 예외</b>이고, 이건 <b>계측 등록의 예외</b>다.
     *
     * <p>{@code finally} 에서 나는 예외는 전파 중이던 예외를 <b>대체</b>한다. 그러니 여기서 삼키지 않으면
     * 오히려 그 규칙이 깨진다: {@code LiveMediaException} 만 잡는 {@code ReconcileExpiredReadyService}
     * 의 방별 스킵이 그 예외를 놓쳐 회차 전체가 죽는다. 관측 실패로 정리 잡을 멈추는 건 옳지 않다.
     */
    private void record(String op, String result, Timer.Sample sample) {
        try {
            sample.stop(Timer.builder(LiveMeterNames.MEDIA_CALL)
                    .tag(LiveMeterNames.TAG_OP, op)
                    .tag(LiveMeterNames.TAG_RESULT, result)
                    .register(registry));
        } catch (RuntimeException e) {
            // 조용히 넘기지는 않는다 — 지표가 비는데 원인을 모르는 상태가 되면 안 된다.
            log.warn("미디어 호출 계측 실패 — 호출 자체는 정상 처리됨. op={}, result={}", op, result, e);
        }
    }
}
