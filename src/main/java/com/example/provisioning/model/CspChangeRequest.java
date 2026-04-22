package com.example.provisioning.model;

/**
 * Input for a CSP (Customer Service Profile) change operation.
 *
 * A CSP change updates the network profile of a SIM card in the HLR
 * (Home Location Register), which controls which services the SIM can access
 * (e.g., data, roaming, voice restrictions).
 */
public class CspChangeRequest {

    private String correlationId;  // unique ID for end-to-end tracing
    private String iccid;          // SIM card identifier
    private String msisdn;         // phone number associated with the SIM
    private String networkId;      // operator network identifier (used by Decomposition Service)
    private String currentCsp;     // current network profile code
    private String targetCsp;      // desired network profile code
    private String requestedBy;    // operator or system initiating the change

    public CspChangeRequest() {}

    public CspChangeRequest(String correlationId, String iccid, String msisdn,
                            String networkId, String currentCsp, String targetCsp,
                            String requestedBy) {
        this.correlationId = correlationId;
        this.iccid = iccid;
        this.msisdn = msisdn;
        this.networkId = networkId;
        this.currentCsp = currentCsp;
        this.targetCsp = targetCsp;
        this.requestedBy = requestedBy;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }

    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String v) { this.msisdn = v; }

    public String getNetworkId() { return networkId; }
    public void setNetworkId(String v) { this.networkId = v; }

    public String getCurrentCsp() { return currentCsp; }
    public void setCurrentCsp(String v) { this.currentCsp = v; }

    public String getTargetCsp() { return targetCsp; }
    public void setTargetCsp(String v) { this.targetCsp = v; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }

    @Override
    public String toString() {
        return String.format(
                "CspChangeRequest{correlationId='%s', iccid='%s', msisdn='%s', network='%s', %s→%s, by='%s'}",
                correlationId, iccid, msisdn, networkId, currentCsp, targetCsp, requestedBy);
    }
}
