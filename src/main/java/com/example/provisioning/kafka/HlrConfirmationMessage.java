package com.example.provisioning.kafka;

/**
 * JSON message published to the hlr-confirmations Kafka topic by the HLR gateway
 * (or KafkaSimulator in tests) after processing a CSP change command.
 *
 * workflowId is carried from the original HlrCommandMessage so the consumer
 * can signal the correct Temporal workflow instance.
 */
public class HlrConfirmationMessage {

    private String correlationId;
    private String workflowId;
    private boolean success;
    private String appliedCsp;   // set on success
    private String errorCode;    // set on failure
    private String errorMessage; // set on failure

    public HlrConfirmationMessage() {}

    public static HlrConfirmationMessage ok(String correlationId, String workflowId, String appliedCsp) {
        HlrConfirmationMessage m = new HlrConfirmationMessage();
        m.correlationId = correlationId;
        m.workflowId = workflowId;
        m.success = true;
        m.appliedCsp = appliedCsp;
        return m;
    }

    public static HlrConfirmationMessage error(String correlationId, String workflowId,
                                               String errorCode, String errorMessage) {
        HlrConfirmationMessage m = new HlrConfirmationMessage();
        m.correlationId = correlationId;
        m.workflowId = workflowId;
        m.success = false;
        m.errorCode = errorCode;
        m.errorMessage = errorMessage;
        return m;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String v) { this.workflowId = v; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }

    public String getAppliedCsp() { return appliedCsp; }
    public void setAppliedCsp(String v) { this.appliedCsp = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    @Override
    public String toString() {
        return success
                ? String.format("HlrConfirmationMessage{OK, correlationId='%s', appliedCsp='%s'}", correlationId, appliedCsp)
                : String.format("HlrConfirmationMessage{ERROR, correlationId='%s', code='%s'}", correlationId, errorCode);
    }
}
