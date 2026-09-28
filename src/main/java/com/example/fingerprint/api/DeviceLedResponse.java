package com.example.fingerprint.api;

public record DeviceLedResponse(
        String deviceId,
        String ledType,
        String availableLeds
) {
}
