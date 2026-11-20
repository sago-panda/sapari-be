package com.sapari.live.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;

class LiveRoomRepositoryImplStatusMapTest {

    @Test
    @DisplayName("도메인 상태 ↔ 영속 enum 표가 양쪽 다 빠짐없다 — 빠지면 그 상태의 방이 판매자 목록에서 사라진다")
    void statusMapIsComplete() {
        assertThat(LiveRoomRepositoryImpl.STATUS_COLUMN.keySet().toArray())
                .containsExactlyInAnyOrder((Object[]) LiveStatus.class.getPermittedSubclasses());
        assertThat(LiveRoomRepositoryImpl.STATUS_COLUMN.values())
                .containsExactlyInAnyOrder(LiveRoomStatus.values());
    }
}
