package com.example.api;

/**
 * POST /api/provisioning/csp-change
 */
public class StartCspChangeRequest {

    private String iccid;
    private String msisdn;
    private String networkId;
    private String currentCsp;
    private String targetCsp;
    private String requestedBy;

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
}
