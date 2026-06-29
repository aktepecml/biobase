# Spring Fingerprint Capture

Spring Boot ile Crossmatch LScan Essentials BioBase 4 DLL'ini cagirip parmak izi verisini dosyaya kaydeden basit REST uygulamasi.

## Gereksinimler

- Java 17+
- Maven
- Cihaz suruculeri ve `LScanEssentialsBioBase4.dll`
- DLL'in `PATH` uzerinde olmasi veya uygulamayi su sekilde baslatman:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dbiobase.dll.path=C:/SDK/LScanEssentialsBioBase4.dll"
```

## Calistirma

```bash
cd spring-fingerprint-capture
mvn spring-boot:run
```

Varsayilan port: `8080`

## Temel Akis

```bash
curl -X POST http://localhost:8080/api/fingerprint/system/open
curl http://localhost:8080/api/fingerprint/devices
curl -X POST "http://localhost:8080/api/fingerprint/devices/DEVICE_ID/open"
curl -X POST "http://localhost:8080/api/fingerprint/devices/DEVICE_ID/capture?position=RightIndex&impression=FingerprintFlat&timeoutSeconds=30"
curl -OJ http://localhost:8080/api/fingerprint/capture/latest
curl -X POST "http://localhost:8080/api/fingerprint/devices/DEVICE_ID/close"
curl -X POST http://localhost:8080/api/fingerprint/system/close
```

`DEVICE_ID`, `/devices` cevabindaki `deviceId` alanidir.

## Endpointler

- `POST /api/fingerprint/system/open`
- `POST /api/fingerprint/system/close`
- `GET /api/fingerprint/devices`
- `GET /api/fingerprint/devices/count`
- `POST /api/fingerprint/devices/{deviceId}/open?reset=false`
- `POST /api/fingerprint/devices/{deviceId}/close?standby=true`
- `GET /api/fingerprint/devices/{deviceId}/status`
- `GET /api/fingerprint/devices/{deviceId}/properties`
- `POST /api/fingerprint/devices/{deviceId}/capture?position=RightIndex&impression=FingerprintFlat`
- `POST /api/fingerprint/devices/{deviceId}/capture/cancel`
- `POST /api/fingerprint/devices/{deviceId}/capture/override`
- `GET /api/fingerprint/preview/latest`
- `POST /api/fingerprint/preview/save`
- `GET /api/fingerprint/capture/latest`

Capture dosyalari varsayilan olarak `captures/` klasorune yazilir.

Not: SDK callback'i hangi formatta veri verirse uygulama onu kaydeder. Preview/capture BMP veya JPG gelirse dogrudan goruntu dosyasi olur; FIR gelirse ham ISO Fingerprint Image Record olarak `.fir` kaydedilir.

## Auto Capture

Capture baslamadan once uygulama cihazdan `DEVICE_AUTOCAPTURE_SUPPORTED` bilgisini okur. Destekleniyorsa:

- `AUTOCAPTURE_ON=TRUE`
- `AUTOCAPTURE_NUM_RQD_OBJECTS=1`

set edilir. `application.properties` uzerinden degistirebilirsin:

```properties
fingerprint.auto-capture-enabled=true
fingerprint.auto-capture-required-objects=1
fingerprint.auto-contrast-enabled=true
fingerprint.image-resolution=500
fingerprint.active-area=0 0 0 0
fingerprint.spoof-detection-enabled=false
fingerprint.auto-capture-override-enabled=false
fingerprint.auto-capture-override-time=4000
fingerprint.auto-capture-override-mode=OnInsufficientQuality
fingerprint.preview-image-format=BMP
fingerprint.preview-level=Medium
```

`ACTIVE_AREA`, `IMAGE_RESOLUTION`, `AUTOCONTRAST_ON`, preview format/level, spoof detection ve auto-capture override ayarlari capture oncesi uygulanir. Opsiyonel property'ler cihazda desteklenmiyorsa uygulama uyarı loglayip capture'a devam eder.
