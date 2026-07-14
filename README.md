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

## Console Modu

Varsayilan olarak uygulama acilinca console runner calisir:

1. BioBase sistemini acar.
2. Ilk cihazi bulup acar.
3. Capture baslatir.
4. Capture bitene kadar son preview callback'ini `captures/preview-live.bmp` dosyasina periyodik olarak yazar.
5. Final capture gelince `captures/capture-...` dosyasina yazar.

REST controller uygulamada kalir. Console runner'i kapatmak istersen:

```properties
fingerprint.console-runner-enabled=false
```

Capture bittikten sonra cihazi ve BioBase sistemini otomatik kapatmak istersen:

```properties
fingerprint.console-close-when-done=true
```

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
fingerprint.capture-timeout-seconds=0
fingerprint.preview-image-format=
fingerprint.preview-level=Medium
fingerprint.preview-timeout-seconds=5
fingerprint.live-preview-file-enabled=true
fingerprint.live-preview-file-name=preview-live
fingerprint.live-preview-write-interval-millis=250
fingerprint.console-runner-enabled=true
fingerprint.console-close-when-done=false
```

`ACTIVE_AREA`, `IMAGE_RESOLUTION`, `AUTOCONTRAST_ON`, preview format/level, spoof detection ve auto-capture override ayarlari capture oncesi uygulanir. Opsiyonel property'ler cihazda desteklenmiyorsa uygulama uyarı loglayip capture'a devam eder.

Live preview dosyasi callback thread'i icinde yazilmaz. Callback yalnizca native preview buffer'ini Java byte dizisine kopyalar; dosya yazma islemi ayri bir worker thread tarafindan yapilir. Bu, BioBase dokumanindaki callback thread'i icinden LSE/BioBase API cagrisi yapmama uyarisina uygun kalmak icindir.

`fingerprint.capture-timeout-seconds=0` final capture gelene kadar bekler. Pozitif bir deger verirsen sure dolunca acquisition iptal edilir. `fingerprint.preview-image-format` varsayilan olarak bostur; cihazin `PREVIEW_IMAGE_FORMAT` property set etmeyi desteklediginden eminsen `BMP` veya `JPG` olarak acabilirsin.
