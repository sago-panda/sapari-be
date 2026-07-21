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
import org.mockito.ArgumentCaptor;
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
import com.sapari.live.domain.model.LiveProduct;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStreamType;
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
                .set("streamType", new LiveStreamType.WebRtc())
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

    @Test
    @DisplayName("방송 시작 시 상품의 sortOrder는 리스트 인덱스로, pinnedAt은 고정 상품에만 세팅된다.")
    @SuppressWarnings("unchecked")
    void start_persists_products_with_sortOrder_and_pinnedAt(){
        // given: 상품 3개 중 index 1만 고정 (고정 상품은 정확히 1개여야 시작 가능)
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        StartLiveCommand.ProductEntry p0 = new StartLiveCommand.ProductEntry(UUID.randomUUID(), 10000, 8000, 7000, false);
        StartLiveCommand.ProductEntry p1 = new StartLiveCommand.ProductEntry(UUID.randomUUID(), 20000, 15000, 12000, true);
        StartLiveCommand.ProductEntry p2 = new StartLiveCommand.ProductEntry(UUID.randomUUID(), 30000, 25000, 20000, false);
        StartLiveCommand multiCommand = new StartLiveCommand(roomId, sellerId, List.of(p0, p1, p2));

        LiveRoom room = scheduledRoom();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn("sfu-token-123");
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(new HlsEgressResult("egress-123", "http://hls.url/index.m3u8"));
        given(timeProvider.now()).willReturn(now);
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        startLiveService.start(multiCommand);

        // then: 저장되는 LiveProduct 리스트를 캡처해 sortOrder/pinnedAt 검증
        ArgumentCaptor<List<LiveProduct>> captor = ArgumentCaptor.forClass(List.class);
        verify(liveProductRepository).saveAll(captor.capture());
        List<LiveProduct> saved = captor.getValue();

        assertThat(saved.size()).isEqualTo(3);
        assertThat(saved.get(0).sortOrder()).isEqualTo(0);
        assertThat(saved.get(1).sortOrder()).isEqualTo(1);
        assertThat(saved.get(2).sortOrder()).isEqualTo(2);
        // 고정 상품(index 1)만 pinnedAt이 세팅되고 나머지는 null
        assertThat(saved.get(0).pinnedAt()).isNull();
        assertThat(saved.get(1).pinnedAt()).isEqualTo(now);
        assertThat(saved.get(2).pinnedAt()).isNull();
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
                .set("streamType", new LiveStreamType.WebRtc())
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

    @Test
    @DisplayName("RTMP 방 시작: OBS 미연결이면 상품만 등록하고 Ready로 저장 — 셀러 토큰·egress 없음")
    void rtmp_start_arms_when_ingress_inactive(){
        // given
        LiveRoom room = rtmpScheduledRoom();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.isIngressActive(roomId)).willReturn(false);
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = startLiveService.start(command);

        // then: 시작 대기(Ready)로 저장, 토큰·HLS·egress 없음, 상품은 등록
        assertThat(result.sfuToken()).isNull();
        assertThat(result.hlsUrl()).isNull();
        verify(liveProductRepository).saveAll(any());
        verify(liveMediaManager, never()).issueSellerToken(any(UUID.class), any(UUID.class));
        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));

        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(LiveStatus.Ready.class);
    }

    @Test
    @DisplayName("RTMP 방 시작: OBS가 이미 연결돼 있으면(랑데부) 즉시 egress 시작하고 Live로 전이한다")
    void rtmp_start_goes_live_when_ingress_active(){
        // given
        LiveRoom room = rtmpScheduledRoom();
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.isIngressActive(roomId)).willReturn(true);
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = startLiveService.start(command);

        // then: Live로 전이, HLS URL 반환, 셀러 토큰은 미발급(RTMP는 ingress push)
        assertThat(result.sfuToken()).isNull();
        assertThat(result.hlsUrl()).isEqualTo(egressResult.hlsUrl());
        verify(liveMediaManager, never()).issueSellerToken(any(UUID.class), any(UUID.class));
        verify(liveMediaManager).startHlsEgress(roomId);

        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(LiveStatus.Live.class);
    }

    private LiveRoom rtmpScheduledRoom(){
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", null, null);
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.Rtmp("ingress-1"))
                .sample();
    }

    private LiveRoom scheduledRoom(){
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc())
                .sample();
    }

    private void triggerAfterCompletion(int status){
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(status));
    }

}
