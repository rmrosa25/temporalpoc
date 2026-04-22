package com.example.provisioning.kafka;

import com.example.provisioning.model.NetworkElement;

/**
 * Kafka message published to the provisioning-commands topic.
 *
 * One message is published per NetworkElement in the ProvisioningPlan.
 * The network element's adapter consumes this and applies the profile change,
 * then publishes an ElementConfirmationMessage to provisioning-confirmations.
 *
 * workflowId is carried so the confirmation consumer can signal the correct
 * Temporal workflow instance. elementId identifies which element confirmed.
 */
public class ProvisioningCommandMessage {

    private String correlationId;
    private String workflowId;
    private String iccid;
    private String elementId;
    private NetworkElement.ElementType elementType;
    private String networkId;
    private String targetProfile;

    public ProvisioningCommandMessage() {}

    public ProvisioningCommandMessage(String correlationId, String workflowId,
                                      String iccid, String elementId,
                                      NetworkElement.ElementType elementType,
                                      String networkId, String targetProfile) {
        this.correlationId = correlationId;
        this.workflowId = workflowId;
        this.iccid = iccid;
        this.elementId = elementId;
        this.elementType = elementType;
        this.networkId = networkId;
        this.targetProfile = targetProfile;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String v) { this.workflowId = v; }

    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }

    public String getElementId() { return elementId; }
    public void setElementId(String v) { this.elementId = v; }

    public NetworkElement.ElementType getElementType() { return elementType; }
    public void setElementType(NetworkElement.ElementType v) { this.elementType = v; }

    public String getNetworkId() { return networkId; }
    public void setNetworkId(String v) { this.networkId = v; }

    public String getTargetProfile() { return targetProfile; }
    public void setTargetProfile(String v) { this.targetProfile = v; }

    @Override
    public String toString() {
        return String.format(
                "ProvisioningCommandMessage{correlationId='%s', elementId='%s', type=%s, profile='%s'}",
                correlationId, elementId, elementType, targetProfile);
    }
}
