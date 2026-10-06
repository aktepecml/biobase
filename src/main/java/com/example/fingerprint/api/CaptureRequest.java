package com.example.fingerprint.api;

public record CaptureRequest(
        String deviceId,
        String position,
        String impression,
        Long timeoutSeconds,
        java.util.List<String> missingFingers
) {
    public CaptureRequest(String deviceId, String position, String impression, Long timeoutSeconds) {
        this(deviceId, position, impression, timeoutSeconds, null);
    }
}
