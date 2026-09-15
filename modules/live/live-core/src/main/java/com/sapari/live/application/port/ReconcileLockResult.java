package com.sapari.live.application.port;

/** 분산 락 획득 시도의 결과. */
public enum ReconcileLockResult {
    ACQUIRED,
    SKIPPED,
    SHUTDOWN,
    FAILED
}
