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
            log.info("Capture output directory: {}", properties.getOutputDir().toAbsolutePath());
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

            log.info("Starting continuous capture loop. Press Ctrl+C to stop application.");

            // Sonsuz döngü başlangıcı
            while (true) {
                try {
                    log.info("Waiting for fingerprint capture...");

                    CaptureResponse capture = service.capture(
                            deviceId,
                            properties.getDefaultPosition(),
                            properties.getDefaultImpression(),
                            properties.getCaptureTimeoutSeconds()
                    );

                    log.info("Capture completed successfully.");

                    // İsteğe bağlı: Her başarılı taramadan sonra kısa bir bekleme (örn: 1 saniye)
                    Thread.sleep(1000);

                } catch (InterruptedException e) {
                    log.info("Capture loop interrupted, exiting...");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Döngü içindeki hatalar (örneğin timeout) döngüyü kırmasın, bir sonraki taramaya geçsin
                    log.error("Error during capture step. Retrying in 2 seconds...", e);
                    Thread.sleep(2000);
                }
            }

        } catch (Exception e) {
            log.error("Critical error in fingerprint system initialization", e);
        } finally {
            // Uygulama kapanırken kaynakları güvenli bir şekilde temizle
            if (deviceId != null) {
                try {
                    service.closeDevice(deviceId, true);
                    log.info("Device closed safely.");
                } catch (Exception e) {
                    log.error("Failed to close device: {}", deviceId, e);
                }
            }
            try {
                service.closeSystem();
                log.info("BioBase system closed.");
            } catch (Exception e) {
                log.error("Failed to close BioBase system", e);
            }
        }
    }
}