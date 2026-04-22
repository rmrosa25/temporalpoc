package com.example.provisioning.model;

/**
 * A single network element that requires a provisioning command.
 *
 * Produced by the Decompose activity. Each element maps to one Kafka message
 * and one confirmation signal back to the workflow.
 *
 * elementType examples: HLR, MSC, SGSN, GGSN, HSS
 * elementId:   the system identifier of that specific node (e.g. "HLR-EU-01")
 * networkId:   the network/operator this element belongs to
 */
public class NetworkElement {

    public enum ElementType { HLR, MSC, SGSN, GGSN, HSS }

    private ElementType elementType;
    private String elementId;
    private String networkId;
    private String targetProfile; // element-specific profile code to apply

    public NetworkElement() {}

    public NetworkElement(ElementType elementType, String elementId,
                          String networkId, String targetProfile) {
        this.elementType = elementType;
        this.elementId = elementId;
        this.networkId = networkId;
        this.targetProfile = targetProfile;
    }

    public ElementType getElementType() { return elementType; }
    public void setElementType(ElementType v) { this.elementType = v; }

    public String getElementId() { return elementId; }
    public void setElementId(String v) { this.elementId = v; }

    public String getNetworkId() { return networkId; }
    public void setNetworkId(String v) { this.networkId = v; }

    public String getTargetProfile() { return targetProfile; }
    public void setTargetProfile(String v) { this.targetProfile = v; }

    @Override
    public String toString() {
        return String.format("NetworkElement{type=%s, id='%s', network='%s', profile='%s'}",
                elementType, elementId, networkId, targetProfile);
    }
}
