package com.example.fingerprint.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fingerprint")
public class FingerprintProperties {
    private Path outputDir = Path.of("captures");
    private String defaultPosition = "RightIndex";
    private String defaultImpression = "FingerprintFlat";
    private long captureTimeoutSeconds = 30;
    private boolean autoCaptureEnabled = true;
    private int autoCaptureRequiredObjects = 1;
    private boolean autoContrastEnabled = true;
    private String imageResolution = "500";
    private String activeArea = "0 0 0 0";
    private boolean spoofDetectionEnabled = false;
    private boolean autoCaptureOverrideEnabled = false;
    private String autoCaptureOverrideTime = "4000";
    private String autoCaptureOverrideMode = "OnInsufficientQuality";
    private String previewImageFormat = "BMP";
    private String previewLevel = "Medium";
    private long previewTimeoutSeconds = 5;
    private boolean livePreviewFileEnabled = true;
    private String livePreviewFileName = "preview-live";
    private long livePreviewWriteIntervalMillis = 250;
    private boolean consoleRunnerEnabled = true;
    private boolean consoleCloseWhenDone = false;

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

    public boolean isAutoCaptureEnabled() {
        return autoCaptureEnabled;
    }

    public void setAutoCaptureEnabled(boolean autoCaptureEnabled) {
        this.autoCaptureEnabled = autoCaptureEnabled;
    }

    public int getAutoCaptureRequiredObjects() {
        return autoCaptureRequiredObjects;
    }

    public void setAutoCaptureRequiredObjects(int autoCaptureRequiredObjects) {
        this.autoCaptureRequiredObjects = autoCaptureRequiredObjects;
    }

    public boolean isAutoContrastEnabled() {
        return autoContrastEnabled;
    }

    public void setAutoContrastEnabled(boolean autoContrastEnabled) {
        this.autoContrastEnabled = autoContrastEnabled;
    }

    public String getImageResolution() {
        return imageResolution;
    }

    public void setImageResolution(String imageResolution) {
        this.imageResolution = imageResolution;
    }

    public String getActiveArea() {
        return activeArea;
    }

    public void setActiveArea(String activeArea) {
        this.activeArea = activeArea;
    }

    public boolean isSpoofDetectionEnabled() {
        return spoofDetectionEnabled;
    }

    public void setSpoofDetectionEnabled(boolean spoofDetectionEnabled) {
        this.spoofDetectionEnabled = spoofDetectionEnabled;
    }

    public boolean isAutoCaptureOverrideEnabled() {
        return autoCaptureOverrideEnabled;
    }

    public void setAutoCaptureOverrideEnabled(boolean autoCaptureOverrideEnabled) {
        this.autoCaptureOverrideEnabled = autoCaptureOverrideEnabled;
    }

    public String getAutoCaptureOverrideTime() {
        return autoCaptureOverrideTime;
    }

    public void setAutoCaptureOverrideTime(String autoCaptureOverrideTime) {
        this.autoCaptureOverrideTime = autoCaptureOverrideTime;
    }

    public String getAutoCaptureOverrideMode() {
        return autoCaptureOverrideMode;
    }

    public void setAutoCaptureOverrideMode(String autoCaptureOverrideMode) {
        this.autoCaptureOverrideMode = autoCaptureOverrideMode;
    }

    public String getPreviewImageFormat() {
        return previewImageFormat;
    }

    public void setPreviewImageFormat(String previewImageFormat) {
        this.previewImageFormat = previewImageFormat;
    }

    public String getPreviewLevel() {
        return previewLevel;
    }

    public void setPreviewLevel(String previewLevel) {
        this.previewLevel = previewLevel;
    }

    public long getPreviewTimeoutSeconds() {
        return previewTimeoutSeconds;
    }

    public void setPreviewTimeoutSeconds(long previewTimeoutSeconds) {
        this.previewTimeoutSeconds = previewTimeoutSeconds;
    }

    public boolean isLivePreviewFileEnabled() {
        return livePreviewFileEnabled;
    }

    public void setLivePreviewFileEnabled(boolean livePreviewFileEnabled) {
        this.livePreviewFileEnabled = livePreviewFileEnabled;
    }

    public String getLivePreviewFileName() {
        return livePreviewFileName;
    }

    public void setLivePreviewFileName(String livePreviewFileName) {
        this.livePreviewFileName = livePreviewFileName;
    }

    public long getLivePreviewWriteIntervalMillis() {
        return livePreviewWriteIntervalMillis;
    }

    public void setLivePreviewWriteIntervalMillis(long livePreviewWriteIntervalMillis) {
        this.livePreviewWriteIntervalMillis = livePreviewWriteIntervalMillis;
    }

    public boolean isConsoleRunnerEnabled() {
        return consoleRunnerEnabled;
    }

    public void setConsoleRunnerEnabled(boolean consoleRunnerEnabled) {
        this.consoleRunnerEnabled = consoleRunnerEnabled;
    }

    public boolean isConsoleCloseWhenDone() {
        return consoleCloseWhenDone;
    }

    public void setConsoleCloseWhenDone(boolean consoleCloseWhenDone) {
        this.consoleCloseWhenDone = consoleCloseWhenDone;
    }
}
