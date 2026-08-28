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
4. Capture bitene kadar son preview callback'ini bellekte tutar.
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
fingerprint.auto-capture-required-objects=4
fingerprint.auto-contrast-enabled=true
fingerprint.image-resolution=500
fingerprint.active-area=0 0 0 0
fingerprint.spoof-detection-enabled=false
fingerprint.auto-capture-override-enabled=true
fingerprint.auto-capture-override-time=4000
fingerprint.auto-capture-override-mode=OnInsufficientCount
fingerprint.capture-timeout-seconds=0
fingerprint.preview-image-format=
fingerprint.preview-level=Medium
fingerprint.preview-timeout-seconds=5
fingerprint.preview-payload-cache-enabled=true
fingerprint.preview-payload-cache-interval-millis=0
fingerprint.preview-segmentation-enabled=false
fingerprint.preview-diagnostics-enabled=true
fingerprint.preview-diagnostics-interval-millis=5000
fingerprint.console-runner-enabled=true
fingerprint.console-close-when-done=false
fingerprint.capture-success-beep-enabled=true
fingerprint.capture-success-beep-pattern=3
fingerprint.capture-success-beep-volume=100
fingerprint.capture-success-beep-delay-millis=200
fingerprint.capture-success-beep-retries=2
fingerprint.capture-progress-beep-enabled=true
fingerprint.capture-progress-beep-pattern=2
fingerprint.capture-progress-beep-volume=100
```

`ACTIVE_AREA`, `IMAGE_RESOLUTION`, `AUTOCONTRAST_ON`, preview format/level, spoof detection ve auto-capture override ayarlari capture oncesi uygulanir. Opsiyonel property'ler cihazda desteklenmiyorsa uygulama uyarı loglayip capture'a devam eder.

Sag dort parmak/slap capture icin tipik ayar:

```properties
fingerprint.default-position=RightFour
fingerprint.default-impression=FingerprintFlat
fingerprint.auto-capture-required-objects=4
fingerprint.auto-capture-override-enabled=true
fingerprint.auto-capture-override-mode=OnInsufficientCount
fingerprint.auto-capture-override-time=4000
```

Live preview callback thread'i icinde dosyaya yazilmaz ve BioBase API cagrisi yapilmaz. Callback native preview header'ini okur; `fingerprint.preview-payload-cache-enabled=true` ise payload'i Java byte dizisine kopyalayip son preview state'ini gunceller. `fingerprint.preview-payload-cache-interval-millis=0` her frame'i cacheler; `50` gibi bir deger Java tarafina saniyede yaklasik 20 frame tasir. `fingerprint.preview-segmentation-enabled=false` preview callback icinde segment parse etmeyi kapatir; final capture sonrasindaki segment fallback'leri calismaya devam eder. Bu, BioBase dokumanindaki callback thread'i icinden LSE/BioBase API cagrisi yapmama uyarisina uygun kalmak ve preview akisini yavaslatmamak icindir. Preview'i dosyaya almak gerekirse `/api/fingerprint/preview/save` manuel olarak cagrilabilir.

`fingerprint.preview-diagnostics-enabled=true` iken capture basinda cihaz preview property'leri loglanir, capture sirasinda da belirli araliklarla preview FPS, cachelenen frame sayisi ve ortalama byte-copy suresi yazilir. Donma analizi icin once bu loglara bakmak gerekir. SDK ornek uygulamasindaki en hizli yol `BioB_SetVisualizationWindow()` ile native preview'i dogrudan Win32 pencereye cizdirmektir; Spring console uygulamasinda pencere handle'i olmadigi icin bu yol ancak dis arayuz uygulamasi bir HWND saglarsa kullanilabilir.

Capture sirasinda SDK'nin `BIOB_OBJECT_COUNT` ve `BIOB_OBJECT_QUALITY` callbackleri de dinlenir. Callback thread'i icinde yalnizca native degerler Java listesine kopyalanir; SDK/BioBase API cagrisi yapilmaz. Son canli state `/api/fingerprint/devices/{deviceId}/status` ve capture response icindeki `objectCount` / `objectQualities` alanlarinda gorulebilir.

Segment bbox akisi oncelik sirasiyla calisir: preview callback icindeki `BioBScene/BioBROI`, FIR icindeki ayri view/segment imagelarinin ana capture image uzerinde eslestirilmesi, son olarak sadece capture image uzerinden yapilan basit goruntu fallback'i. FIR icindeki en buyuk view ana capture image olarak kaydedilir.

Final image geldikten ve cihaz acquisition state'i durduktan sonra `BioB_SetOutputData` ile cihaz beeper'i tetiklenir. Bunu kapatmak icin `fingerprint.capture-success-beep-enabled=false` verilebilir; pattern, volume, kisa bekleme suresi ve retry sayisi propertylerden degistirilebilir. Roll capture'da `BIOB_ACQUISITION_STARTED` event'i geldiğinde, callback thread'i disindan, ayrica progress beep gonderilir; bunu `fingerprint.capture-progress-beep-enabled=false` ile kapatabilirsin. Cihaz `DEVICE_BEEPER_TYPE=BEEPER_NONE` donerse beep atlanir ve warn log basilir.

`fingerprint.capture-timeout-seconds=0` final capture gelene kadar bekler. Pozitif bir deger verirsen sure dolunca acquisition iptal edilir. `fingerprint.preview-image-format` varsayilan olarak bostur; cihazin `PREVIEW_IMAGE_FORMAT` property set etmeyi desteklediginden eminsen `BMP` veya `JPG` olarak acabilirsin.
