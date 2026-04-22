package com.example.provisioning.kafka;

/**
 * JSON message published to the hlr-commands Kafka topic.
 *
 * The HLR gateway consumes this and applies the CSP change in the network.
 * correlationId is used to match the confirmation back to the workflow.
 */
public class HlrCommandMessage {

    private String correlationId;
    private String workflowId;    // Temporal workflow ID — consumer uses this to signal back
    private String iccid;
    private String msisdn;
    private String targetCsp;
    private String requestedBy;

    public HlrCommandMessage() {}

    public HlrCommandMessage(String correlationId, String workflowId, String iccid,
                             String msisdn, String targetCsp, String requestedBy) {
        this.correlationId = correlationId;
        this.workflowId = workflowId;
        this.iccid = iccid;
        this.msisdn = msisdn;
        this.targetCsp = targetCsp;
        this.requestedBy = requestedBy;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String v) { this.workflowId = v; }

    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }

    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String v) { this.msisdn = v; }

    public String getTargetCsp() { return targetCsp; }
    public void setTargetCsp(String v) { this.targetCsp = v; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }

    @Override
    public String toString() {
        return String.format("HlrCommandMessage{correlationId='%s', iccid='%s', targetCsp='%s'}",
                correlationId, iccid, targetCsp);
    }
}
