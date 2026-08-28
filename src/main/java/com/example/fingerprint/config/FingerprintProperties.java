package com.example.fingerprint.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fingerprint")
public class FingerprintProperties {
    private Path outputDir = Path.of("captures");
    private String defaultPosition = "RightIndex";
    private String defaultImpression = "FingerprintFlat";
    private long captureTimeoutSeconds = 0;
    private boolean autoCaptureEnabled = true;
    private int autoCaptureRequiredObjects = 1;
    private boolean autoContrastEnabled = true;
    private String imageResolution = "500";
    private String activeArea = "0 0 0 0";
    private boolean spoofDetectionEnabled = false;
    private boolean autoCaptureOverrideEnabled = true;
    private String autoCaptureOverrideTime = "4000";
    private String autoCaptureOverrideMode = "OnInsufficientCount";
    private String previewImageFormat = "";
    private String previewLevel = "Medium";
    private long previewTimeoutSeconds = 5;
    private boolean previewPayloadCacheEnabled = true;
    private long previewPayloadCacheIntervalMillis = 0;
    private boolean previewSegmentationEnabled = true;
    private boolean previewDiagnosticsEnabled = true;
    private long previewDiagnosticsIntervalMillis = 5000;
    private boolean consoleRunnerEnabled = true;
    private boolean consoleCloseWhenDone = false;
    private boolean captureSuccessBeepEnabled = true;
    private String captureSuccessBeepPattern = "3";
    private String captureSuccessBeepVolume = "100";
    private long captureSuccessBeepDelayMillis = 200;
    private boolean captureProgressBeepEnabled = true;
    private String captureProgressBeepPattern = "2";
    private String captureProgressBeepVolume = "100";

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

    public boolean isPreviewPayloadCacheEnabled() {
        return previewPayloadCacheEnabled;
    }

    public void setPreviewPayloadCacheEnabled(boolean previewPayloadCacheEnabled) {
        this.previewPayloadCacheEnabled = previewPayloadCacheEnabled;
    }

    public long getPreviewPayloadCacheIntervalMillis() {
        return previewPayloadCacheIntervalMillis;
    }

    public void setPreviewPayloadCacheIntervalMillis(long previewPayloadCacheIntervalMillis) {
        this.previewPayloadCacheIntervalMillis = previewPayloadCacheIntervalMillis;
    }

    public boolean isPreviewSegmentationEnabled() {
        return previewSegmentationEnabled;
    }

    public void setPreviewSegmentationEnabled(boolean previewSegmentationEnabled) {
        this.previewSegmentationEnabled = previewSegmentationEnabled;
    }

    public boolean isPreviewDiagnosticsEnabled() {
        return previewDiagnosticsEnabled;
    }

    public void setPreviewDiagnosticsEnabled(boolean previewDiagnosticsEnabled) {
        this.previewDiagnosticsEnabled = previewDiagnosticsEnabled;
    }

    public long getPreviewDiagnosticsIntervalMillis() {
        return previewDiagnosticsIntervalMillis;
    }

    public void setPreviewDiagnosticsIntervalMillis(long previewDiagnosticsIntervalMillis) {
        this.previewDiagnosticsIntervalMillis = previewDiagnosticsIntervalMillis;
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

    public boolean isCaptureSuccessBeepEnabled() {
        return captureSuccessBeepEnabled;
    }

    public void setCaptureSuccessBeepEnabled(boolean captureSuccessBeepEnabled) {
        this.captureSuccessBeepEnabled = captureSuccessBeepEnabled;
    }

    public String getCaptureSuccessBeepPattern() {
        return captureSuccessBeepPattern;
    }

    public void setCaptureSuccessBeepPattern(String captureSuccessBeepPattern) {
        this.captureSuccessBeepPattern = captureSuccessBeepPattern;
    }

    public String getCaptureSuccessBeepVolume() {
        return captureSuccessBeepVolume;
    }

    public void setCaptureSuccessBeepVolume(String captureSuccessBeepVolume) {
        this.captureSuccessBeepVolume = captureSuccessBeepVolume;
    }

    public long getCaptureSuccessBeepDelayMillis() {
        return captureSuccessBeepDelayMillis;
    }

    public void setCaptureSuccessBeepDelayMillis(long captureSuccessBeepDelayMillis) {
        this.captureSuccessBeepDelayMillis = captureSuccessBeepDelayMillis;
    }

    public boolean isCaptureProgressBeepEnabled() {
        return captureProgressBeepEnabled;
    }

    public void setCaptureProgressBeepEnabled(boolean captureProgressBeepEnabled) {
        this.captureProgressBeepEnabled = captureProgressBeepEnabled;
    }

    public String getCaptureProgressBeepPattern() {
        return captureProgressBeepPattern;
    }

    public void setCaptureProgressBeepPattern(String captureProgressBeepPattern) {
        this.captureProgressBeepPattern = captureProgressBeepPattern;
    }

    public String getCaptureProgressBeepVolume() {
        return captureProgressBeepVolume;
    }

    public void setCaptureProgressBeepVolume(String captureProgressBeepVolume) {
        this.captureProgressBeepVolume = captureProgressBeepVolume;
    }
}
