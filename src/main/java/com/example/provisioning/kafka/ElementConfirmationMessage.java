package com.example.provisioning.kafka;

import com.example.provisioning.model.NetworkElement;

/**
 * Kafka message published to the provisioning-confirmations topic.
 *
 * Published by each network element adapter (or ProvisioningSimulator in tests)
 * after applying the profile change. One message per NetworkElement.
 *
 * The HlrConfirmationDispatcher consumes these and delivers each as an
 * elementConfirmationReceived signal to the waiting CspChangeWorkflow.
 */
public class ElementConfirmationMessage {

    private String correlationId;
    private String workflowId;
    private String elementId;
    private NetworkElement.ElementType elementType;
    private boolean success;
    private String appliedProfile; // set on success
    private String errorCode;      // set on failure
    private String errorMessage;   // set on failure

    public ElementConfirmationMessage() {}

    public static ElementConfirmationMessage ok(String correlationId, String workflowId,
                                                String elementId,
                                                NetworkElement.ElementType elementType,
                                                String appliedProfile) {
        ElementConfirmationMessage m = new ElementConfirmationMessage();
        m.correlationId = correlationId;
        m.workflowId = workflowId;
        m.elementId = elementId;
        m.elementType = elementType;
        m.success = true;
        m.appliedProfile = appliedProfile;
        return m;
    }

    public static ElementConfirmationMessage error(String correlationId, String workflowId,
                                                   String elementId,
                                                   NetworkElement.ElementType elementType,
                                                   String errorCode, String errorMessage) {
        ElementConfirmationMessage m = new ElementConfirmationMessage();
        m.correlationId = correlationId;
        m.workflowId = workflowId;
        m.elementId = elementId;
        m.elementType = elementType;
        m.success = false;
        m.errorCode = errorCode;
        m.errorMessage = errorMessage;
        return m;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String v) { this.workflowId = v; }

    public String getElementId() { return elementId; }
    public void setElementId(String v) { this.elementId = v; }

    public NetworkElement.ElementType getElementType() { return elementType; }
    public void setElementType(NetworkElement.ElementType v) { this.elementType = v; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }

    public String getAppliedProfile() { return appliedProfile; }
    public void setAppliedProfile(String v) { this.appliedProfile = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    @Override
    public String toString() {
        return success
                ? String.format("ElementConfirmationMessage{OK, correlationId='%s', elementId='%s', type=%s}",
                        correlationId, elementId, elementType)
                : String.format("ElementConfirmationMessage{ERROR, correlationId='%s', elementId='%s', code='%s'}",
                        correlationId, elementId, errorCode);
    }
}
