package com.example.provisioning.model;

import java.util.List;

/**
 * Output of the Decompose activity.
 *
 * Describes which network elements must be provisioned to complete a CSP change.
 * The workflow fans out one provisioningCommand per element and waits for all
 * confirmations before proceeding to updateSimInventory.
 */
public class ProvisioningPlan {

    private String correlationId;
    private String iccid;
    private String targetCsp;
    private List<NetworkElement> elements; // one command + one signal per element

    public ProvisioningPlan() {}

    public ProvisioningPlan(String correlationId, String iccid,
                            String targetCsp, List<NetworkElement> elements) {
        this.correlationId = correlationId;
        this.iccid = iccid;
        this.targetCsp = targetCsp;
        this.elements = elements;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }

    public String getTargetCsp() { return targetCsp; }
    public void setTargetCsp(String v) { this.targetCsp = v; }

    public List<NetworkElement> getElements() { return elements; }
    public void setElements(List<NetworkElement> v) { this.elements = v; }

    @Override
    public String toString() {
        return String.format("ProvisioningPlan{correlationId='%s', iccid='%s', targetCsp='%s', elements=%s}",
                correlationId, iccid, targetCsp, elements);
    }
}
