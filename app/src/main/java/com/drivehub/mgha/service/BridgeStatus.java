package com.drivehub.mgha.service;

public final class BridgeStatus {
    public static volatile boolean running;
    public static volatile boolean wifiOk;
    public static volatile boolean carOk;
    public static volatile boolean lastSendOk;
    public static volatile String lastMessage = "";
    public static volatile String lastPreview = "";
    public static volatile long lastSendAtMs;
    public static volatile int lastOkCount;
    public static volatile int lastFailCount;

    private BridgeStatus() {}
}
