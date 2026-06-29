package com.example.fingerprint.biobase;

import com.example.fingerprint.api.CaptureResponse;
import com.example.fingerprint.config.FingerprintProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FingerprintConsoleRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FingerprintConsoleRunner.class);

    private final FingerprintCaptureService service;
    private final FingerprintProperties properties;

    public FingerprintConsoleRunner(FingerprintCaptureService service, FingerprintProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConsoleRunnerEnabled()) {
            return;
        }

        String deviceId = null;
        try {
            log.info("Console runner started. Opening BioBase system.");
            service.openSystem();

            List<DeviceInfo> devices = service.devices();
            if (devices.isEmpty()) {
                log.warn("No BioBase device found.");
                return;
            }

            DeviceInfo device = devices.get(0);
            deviceId = device.deviceId();
            log.info("Opening first BioBase device: {} / {}", device.modelName(), deviceId);
            service.openDevice(deviceId, false);

            log.info("Starting capture. Preview will be saved first, final capture will be saved when available.");
            CaptureResponse capture = service.capture(
                    deviceId,
                    properties.getDefaultPosition(),
                    properties.getDefaultImpression(),
                    properties.getCaptureTimeoutSeconds()
            );
            log.info("Capture saved to {}", capture.savedPath());
        } catch (Exception e) {
            log.error("Console runner failed: {}", e.getMessage(), e);
        } finally {
            if (properties.isConsoleCloseWhenDone() && deviceId != null) {
                try {
                    service.closeDevice(deviceId, true);
                    service.closeSystem();
                    log.info("BioBase system closed.");
                } catch (Exception e) {
                    log.warn("Could not close BioBase system cleanly: {}", e.getMessage());
                }
            }
        }
    }
}
