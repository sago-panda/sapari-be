package com.sapari.live.command;

public record GetLiveCommand(int limit) {

    private static final int DEFAULT_LIMIT = 10;

    public static GetLiveCommand defaultMain() {
        return new GetLiveCommand(DEFAULT_LIMIT);
    }
}
