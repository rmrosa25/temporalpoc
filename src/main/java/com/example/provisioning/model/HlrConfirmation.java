package com.example.provisioning.model;

/**
 * Payload received via Kafka (and delivered as a Temporal Signal) when the HLR
 * finishes processing a CSP change command.
 *
 * In production a Kafka consumer would call the Temporal signal endpoint
 * after consuming the HLR response topic. Here it is simulated by the
 * workflow's signal sender stub.
 */
public class HlrConfirmation {

    private String correlationId;
    private boolean success;
    private String errorCode;    // null on success
    private String errorMessage; // null on success
    private String appliedCsp;   // the CSP actually applied by the HLR

    public HlrConfirmation() {}

    public HlrConfirmation(String correlationId, boolean success,
                           String errorCode, String errorMessage, String appliedCsp) {
        this.correlationId = correlationId;
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.appliedCsp = appliedCsp;
    }

    public static HlrConfirmation ok(String correlationId, String appliedCsp) {
        return new HlrConfirmation(correlationId, true, null, null, appliedCsp);
    }

    public static HlrConfirmation error(String correlationId, String code, String message) {
        return new HlrConfirmation(correlationId, false, code, message, null);
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public String getAppliedCsp() { return appliedCsp; }
    public void setAppliedCsp(String v) { this.appliedCsp = v; }

    @Override
    public String toString() {
        return success
                ? String.format("HlrConfirmation{OK, appliedCsp='%s'}", appliedCsp)
                : String.format("HlrConfirmation{ERROR, code='%s', msg='%s'}", errorCode, errorMessage);
    }
}
