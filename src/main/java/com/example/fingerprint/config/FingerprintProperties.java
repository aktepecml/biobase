package com.example.fingerprint.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fingerprint")
public class FingerprintProperties {
    private Path outputDir = Path.of("captures");
    private String defaultPosition = "RightIndex";
    private String defaultImpression = "FingerprintFlat";
    private long captureTimeoutSeconds = 30;

    public Path getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public String getDefaultPosition() {
        return defaultPosition;
    }

    public void setDefaultPosition(String defaultPosition) {
        this.defaultPosition = defaultPosition;
    }

    public String getDefaultImpression() {
        return defaultImpression;
    }

    public void setDefaultImpression(String defaultImpression) {
        this.defaultImpression = defaultImpression;
    }

    public long getCaptureTimeoutSeconds() {
        return captureTimeoutSeconds;
    }

    public void setCaptureTimeoutSeconds(long captureTimeoutSeconds) {
        this.captureTimeoutSeconds = captureTimeoutSeconds;
    }
}
