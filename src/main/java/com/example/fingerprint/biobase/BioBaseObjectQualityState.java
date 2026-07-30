package com.example.fingerprint.biobase;

public enum BioBaseObjectQualityState {
    BIOB_OBJECT_NOT_PRESENT(0),
    BIOB_OBJECT_GOOD(1),
    BIOB_OBJECT_TOO_LIGHT(2),
    BIOB_OBJECT_TOO_DARK(3),
    BIOB_OBJECT_BAD_SHAPE(4),
    BIOB_OBJECT_POSITION_NOT_OK(5),
    BIOB_OBJECT_CORE_NOT_PRESENT(6),
    BIOB_OBJECT_TRACKING_NOT_OK(7),
    BIOB_OBJECT_POSITION_TOO_HIGH(8),
    BIOB_OBJECT_POSITION_TOO_LEFT(9),
    BIOB_OBJECT_POSITION_TOO_RIGHT(10),
    BIOB_OBJECT_POSITION_TOO_LOW(11),
    BIOB_OBJECT_FLEX_POSITION_TOO_HIGH(12),
    BIOB_OBJECT_FLEX_POSITION_TOO_LEFT(13),
    BIOB_OBJECT_FLEX_POSITION_TOO_RIGHT(14),
    BIOB_OBJECT_FLEX_POSITION_TOO_LOW(15),
    BIOB_OBJECT_TOO_CLOSE(16),
    BIOB_OBJECT_TOO_FAR(17),
    BIOB_OBJECT_NOT_FOCUSED(18),
    BIOB_OBJECT_NOT_STILL(19),
    BIOB_OBJECT_NOT_ALIGNED(20),
    BIOB_OBJECT_OCCLUSION(21),
    BIOB_OBJECT_CONFUSION(22),
    BIOB_OBJECT_ROTATED_CLOCKWISE(23),
    BIOB_OBJECT_ROTATED_COUNTERCLOCKWISE(24),
    UNKNOWN(-1);

    private final int value;

    BioBaseObjectQualityState(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BioBaseObjectQualityState fromValue(int value) {
        for (BioBaseObjectQualityState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
