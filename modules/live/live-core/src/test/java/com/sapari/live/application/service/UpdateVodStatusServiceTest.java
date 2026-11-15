package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;

@DisplayName("UpdateVodStatusService — 조건부 갱신을 저장소에 위임한다")
class UpdateVodStatusServiceTest {

    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final UpdateVodStatusService service = new UpdateVodStatusService(repository);

    @Test
    @DisplayName("저장소에 egressId·목표·사유를 그대로 넘긴다")
    void delegates() {
        given(repository.advanceVodStatus("eg-1", VodStatus.FAILED, "boom")).willReturn(true);

        service.advance("eg-1", VodStatus.FAILED, "boom");

        then(repository).should().advanceVodStatus("eg-1", VodStatus.FAILED, "boom");
    }

    @Test
    @DisplayName("0행(중복·역순·모르는 egress)은 정상 — 예외 없이 끝난다")
    void noRowIsNormal() {
        given(repository.advanceVodStatus("eg-1", VodStatus.PROCESSING, null)).willReturn(false);

        assertThatCode(() -> service.advance("eg-1", VodStatus.PROCESSING, null)).doesNotThrowAnyException();
    }
}
