package com.sapari.global.time;

import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import org.springframework.stereotype.Component;

/**
 * 공통된 time을 저장하기 위함
 */
@Component
@RequiredArgsConstructor
public class TimeProvider {

    private final Clock clock;

    public Instant now() {
        return clock.instant();
    }

    public Date nowAsDate() {
        return Date.from(clock.instant());
    }
}
