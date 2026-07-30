package com.example.fingerprint.biobase;

public enum BioBaseObjectCountState {
    BIOB_OBJECT_COUNT_OK(0),
    BIOB_TOO_MANY_OBJECTS(1),
    BIOB_TOO_FEW_OBJECTS(2),
    UNKNOWN(-1);

    private final int value;

    BioBaseObjectCountState(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BioBaseObjectCountState fromValue(int value) {
        for (BioBaseObjectCountState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
