package com.example.fingerprint.biobase;

public class BioBaseException extends RuntimeException {
    private final int returnCode;

    public BioBaseException(String message) {
        super(message);
        this.returnCode = Integer.MIN_VALUE;
    }

    public BioBaseException(String operation, int returnCode) {
        super(operation + " failed with BioBase code " + returnCode + " (" + BioBaseReturnCode.fromValue(returnCode) + ")");
        this.returnCode = returnCode;
    }

    public int getReturnCode() {
        return returnCode;
    }
}
