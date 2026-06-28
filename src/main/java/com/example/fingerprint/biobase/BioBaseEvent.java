package com.example.fingerprint.biobase;

public enum BioBaseEvent {
    BIOB_INIT_PROGRESS(0),
    BIOB_PREVIEW(1),
    BIOB_OBJECT_QUALITY(2),
    BIOB_OBJECT_COUNT(3),
    BIOB_SCANNER_USERINPUT(4),
    BIOB_SCANNER_USEROUTPUT(5),
    BIOB_ACQUISITION_STARTED(6),
    BIOB_ACQUISITION_COMPLETED(7),
    BIOB_DATA_AVAILABLE(8),
    BIOB_SCANNER_DISCONNECTED(9),
    BIOB_OBJECT_DETECTED(11),
    BIOB_DEVICE_COUNT_CHANGED(12);

    private final int value;

    BioBaseEvent(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
