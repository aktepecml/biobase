package com.example.fingerprint.api;

import com.example.fingerprint.biobase.BioBaseException;
import com.example.fingerprint.biobase.CapturedData;
import com.example.fingerprint.biobase.DeviceInfo;
import com.example.fingerprint.biobase.FingerprintCaptureService;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fingerprint")
public class FingerprintController {
    private final FingerprintCaptureService service;

    public FingerprintController(FingerprintCaptureService service) {
        this.service = service;
    }

    @PostMapping("/system/open")
    public ApiMessage openSystem() {
        service.openSystem();
        return new ApiMessage("BioBase system opened.");
    }

    @PostMapping("/system/close")
    public ApiMessage closeSystem() {
        service.closeSystem();
        return new ApiMessage("BioBase system closed.");
    }

    @GetMapping("/devices/count")
    public int deviceCount() {
        return service.deviceCount();
    }

    @GetMapping("/devices")
    public List<DeviceInfo> devices() {
        return service.devices();
    }

    @PostMapping("/devices/{deviceId}/open")
    public ApiMessage openDevice(@PathVariable String deviceId, @RequestParam(defaultValue = "false") boolean reset) {
        service.openDevice(deviceId, reset);
        return new ApiMessage("Device opened: " + deviceId);
    }

    @PostMapping("/devices/{deviceId}/close")
    public ApiMessage closeDevice(@PathVariable String deviceId, @RequestParam(defaultValue = "true") boolean standby) {
        service.closeDevice(deviceId, standby);
        return new ApiMessage("Device closed: " + deviceId);
    }

    @GetMapping("/devices/{deviceId}/status")
    public DeviceStatusResponse status(@PathVariable String deviceId) {
        return service.status(deviceId);
    }

    @GetMapping(value = "/devices/{deviceId}/properties", produces = MediaType.APPLICATION_XML_VALUE)
    public String properties(@PathVariable String deviceId) {
        return service.propertiesXml(deviceId);
    }

    @PostMapping("/capture")
    public CaptureResponse capture(@RequestBody(required = false) CaptureRequest request) {
        CaptureRequest body = request == null ? new CaptureRequest(null, null, null, null) : request;
        return service.capture(body.deviceId(), body.position(), body.impression(), body.timeoutSeconds());
    }

    @PostMapping("/devices/{deviceId}/capture")
    public CaptureResponse captureDevice(
            @PathVariable String deviceId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String impression,
            @RequestParam(required = false) Long timeoutSeconds
    ) {
        return service.capture(deviceId, position, impression, timeoutSeconds);
    }

    @PostMapping("/devices/{deviceId}/capture/cancel")
    public ApiMessage cancel(@PathVariable String deviceId) {
        service.cancel(deviceId);
        return new ApiMessage("Capture cancelled: " + deviceId);
    }

    @PostMapping("/devices/{deviceId}/capture/override")
    public ApiMessage overrideCapture(@PathVariable String deviceId) {
        service.overrideCapture(deviceId);
        return new ApiMessage("Capture override requested: " + deviceId);
    }

    @GetMapping("/preview/latest")
    public ResponseEntity<byte[]> latestPreview() {
        return binary(service.lastPreview().orElseThrow(() -> new BioBaseException("No preview image has been received yet.")), "latest-preview");
    }

    @PostMapping("/preview/save")
    public CaptureResponse savePreview() {
        return service.saveLastPreview();
    }

    @GetMapping("/capture/latest")
    public ResponseEntity<byte[]> latestCapture() {
        return binary(service.lastCapture().orElseThrow(() -> new BioBaseException("No capture data has been received yet.")), "latest-capture");
    }

    @ExceptionHandler(BioBaseException.class)
    public ResponseEntity<ApiMessage> handleBioBase(BioBaseException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiMessage(exception.getMessage()));
    }

    private static ResponseEntity<byte[]> binary(CapturedData data, String baseName) {
        String filename = baseName + "." + data.format().extension();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.format().mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(data.bytes());
    }
}
