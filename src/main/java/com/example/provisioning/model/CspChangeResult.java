package com.example.provisioning.model;

public class CspChangeResult {

    public enum Status { COMPLETED, FAILED, TIMED_OUT }

    private String correlationId;
    private String iccid;
    private Status status;
    private String appliedCsp;   // set on COMPLETED
    private String errorCode;    // set on FAILED
    private String errorMessage; // set on FAILED or TIMED_OUT

    public CspChangeResult() {}

    private CspChangeResult(String correlationId, String iccid, Status status,
                            String appliedCsp, String errorCode, String errorMessage) {
        this.correlationId = correlationId;
        this.iccid = iccid;
        this.status = status;
        this.appliedCsp = appliedCsp;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static CspChangeResult completed(String correlationId, String iccid, String appliedCsp) {
        return new CspChangeResult(correlationId, iccid, Status.COMPLETED, appliedCsp, null, null);
    }

    public static CspChangeResult failed(String correlationId, String iccid,
                                         String errorCode, String errorMessage) {
        return new CspChangeResult(correlationId, iccid, Status.FAILED, null, errorCode, errorMessage);
    }

    public static CspChangeResult timedOut(String correlationId, String iccid) {
        return new CspChangeResult(correlationId, iccid, Status.TIMED_OUT, null,
                "HLR_TIMEOUT", "No confirmation received from HLR within the allowed window");
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }

    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }

    public String getAppliedCsp() { return appliedCsp; }
    public void setAppliedCsp(String v) { this.appliedCsp = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    @Override
    public String toString() {
        return status == Status.COMPLETED
                ? String.format("CspChangeResult{%s, iccid='%s', appliedCsp='%s'}", status, iccid, appliedCsp)
                : String.format("CspChangeResult{%s, iccid='%s', error='%s: %s'}", status, iccid, errorCode, errorMessage);
    }
}
