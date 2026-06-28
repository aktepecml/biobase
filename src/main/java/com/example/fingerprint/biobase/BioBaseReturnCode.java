package com.example.fingerprint.biobase;

import java.util.HashMap;
import java.util.Map;

public enum BioBaseReturnCode {
    BIOB_SUCCESS(0),
    BIOB_FAILURE(-1),
    BIOB_GENERAL_FAIL(-2),
    BIOB_INVALID_PARAM_VALUE(-3),
    BIOB_MEM_ALLOC(-4),
    BIOB_NOT_SUPPORTED(-5),
    BIOB_RESOURCE_MISSING(-15),
    BIOB_NODEVICE(-105),
    BIOB_DEVICEBUSY(-111),
    BIOB_CAPTUREINPROGRESS(-207),
    BIOB_NOTCAPTURING(-208),
    BIOB_CAPTURETIMEOUT(-210),
    BIOB_ZERO_DEVICES_DETECTED(111),
    BIOB_REPLACE_PAD(128),
    UNKNOWN(Integer.MIN_VALUE);

    private static final Map<Integer, BioBaseReturnCode> BY_VALUE = new HashMap<>();

    static {
        for (BioBaseReturnCode code : values()) {
            BY_VALUE.put(code.value, code);
        }
    }

    private final int value;

    BioBaseReturnCode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BioBaseReturnCode fromValue(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}
