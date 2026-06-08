package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStatus.Live;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveProductRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
public class StartLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveProductRepository liveProductRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private StartLiveService startLiveService;

    private FixtureMonkey fixtureMonkey;

    private StartLiveCommand command;
    private UUID roomId;
    private UUID sellerId;

    @BeforeEach
    void setup(){
        // 보상 훅(registerSynchronization)은 활성 동기화를 요구 — 단위 테스트에는 실제 tx가 없으므로 수동 초기화
        TransactionSynchronizationManager.initSynchronization();

        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();

        roomId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        StartLiveCommand.ProductEntry pinnedProduct = new StartLiveCommand.ProductEntry(
                UUID.randomUUID(), 10000, 8000, 7000, true
        );
        command = new StartLiveCommand(roomId, sellerId, List.of(pinnedProduct));

    }

    @AfterEach
    void clearTxSynchronization(){
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    //TODO: 방송 시작 성공 후 알림 발송 추가
    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 성공: 유효한 요청 시 토큰과 HLS URL을 반환하고 방 상태를 업데이트한다.")
    void execute_success(){
        //given
        String expectedSfuToken = "sfu-token-123";
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");
        LiveRoom room = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamInfo", streamInfo)
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn(expectedSfuToken);
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);
        given(timeProvider.now()).willReturn(Instant.now());

        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

        //when
        var result = startLiveService.start(command);

        //then
        assertThat(result.roomId()).isEqualTo(roomId.toString());
        assertThat(result.sfuToken()).isEqualTo(expectedSfuToken);
        assertThat(result.hlsUrl()).isEqualTo(egressResult.hlsUrl());

        // 검증: Repository와 MediaManager가 올바르게 호출되었는지 확인
        verify(liveMediaManager).issueSellerToken(roomId, sellerId);
        verify(liveMediaManager).startHlsEgress(roomId);
        verify(liveRoomRepository).save(any(LiveRoom.class));
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 실패: 해당하는 방을 찾을 수 없어 에러 발생")
    void execute_fail_room_not_found(){
        // given
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> startLiveService.start(command))
                .isInstanceOf(LiveNotFoundException.class)
                .hasMessageContaining(roomId.toString());

    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 실패: 이미 방송 중이거나 종료된 방")
    void execute_fail_when_invalid_status(){
        // given
        // 1. 방송을 시작할 수 없는 상태(ex: 이미 종료된 상태)로 세팅
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");

        LiveRoom invalidRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("streamInfo", streamInfo)
                .set("status", new Live(Instant.now(), streamInfo.sfuRoomId(), streamInfo.egressId(), streamInfo.hlsUrl()))
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(invalidRoom));

        // when & then
        assertThatThrownBy(() -> startLiveService.start(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .hasMessageContaining(roomId.toString());
    }

    @Test
    @DisplayName("트랜잭션 동기화가 비활성이면 egress를 시작하기 전에 실패한다 — 고아 egress 방지 사전 가드.")
    void fails_before_egress_when_synchronization_inactive(){
        // given: tx 없이 호출되는 회귀 상황 재현
        TransactionSynchronizationManager.clearSynchronization();

        LiveRoom room = scheduledRoom();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn("sfu-token-123");

        // when & then
        assertThatThrownBy(() -> startLiveService.start(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션 동기화 비활성");

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
    }

    @Test
    @DisplayName("egress 시작 후 DB 저장 실패로 롤백되면 보상 훅이 egress를 중단한다.")
    void compensation_stops_egress_on_rollback(){
        // given
        LiveRoom room = scheduledRoom();
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn("sfu-token-123");
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willThrow(new RuntimeException("DB down"));

        // when
        assertThatThrownBy(() -> startLiveService.start(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        // 실제 환경에서 Spring tx 인터셉터가 롤백 시 수행하는 afterCompletion 호출을 수동 재현
        triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        // then
        verify(liveMediaManager).stopHlsEgress(roomId, "egress-123");
    }

    @Test
    @DisplayName("보상 중단(stopHlsEgress)이 실패해도 예외가 전파되지 않는다.")
    void compensation_failure_is_not_propagated(){
        // given
        LiveRoom room = scheduledRoom();
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn("sfu-token-123");
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willThrow(new RuntimeException("DB down"));
        willThrow(new RuntimeException("media server down"))
                .given(liveMediaManager).stopHlsEgress(roomId, "egress-123");

        assertThatThrownBy(() -> startLiveService.start(command)).hasMessage("DB down");

        // when & then: 보상 실패는 로그만 남기고 삼켜진다
        assertThatCode(() -> triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STATUS_UNKNOWN이면 보상하지 않는다 — 커밋됐을 수 있는 방송의 egress를 끊으면 안 된다.")
    void compensation_skipped_on_unknown_status(){
        // given
        LiveRoom room = scheduledRoom();
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn("sfu-token-123");
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> startLiveService.start(command)).hasMessage("DB down");

        // when
        triggerAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        // then
        verify(liveMediaManager, never()).stopHlsEgress(any(UUID.class), anyString());
    }

    private LiveRoom scheduledRoom(){
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamInfo", streamInfo)
                .sample();
    }

    private void triggerAfterCompletion(int status){
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(status));
    }

}
