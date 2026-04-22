package com.example.provisioning.model;

/**
 * Signal payload delivered when one network element finishes provisioning.
 *
 * The workflow receives one ElementConfirmation per NetworkElement in the plan.
 * It waits until all elements have confirmed (or any one fails/times out).
 *
 * elementId matches NetworkElement.elementId so the workflow can track
 * which elements are still pending.
 */
public class ElementConfirmation {

    private String correlationId;
    private String elementId;
    private NetworkElement.ElementType elementType;
    private boolean success;
    private String appliedProfile; // set on success
    private String errorCode;      // set on failure
    private String errorMessage;   // set on failure

    public ElementConfirmation() {}

    private ElementConfirmation(String correlationId, String elementId,
                                NetworkElement.ElementType elementType, boolean success,
                                String appliedProfile, String errorCode, String errorMessage) {
        this.correlationId = correlationId;
        this.elementId = elementId;
        this.elementType = elementType;
        this.success = success;
        this.appliedProfile = appliedProfile;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static ElementConfirmation ok(String correlationId, String elementId,
                                         NetworkElement.ElementType elementType,
                                         String appliedProfile) {
        return new ElementConfirmation(correlationId, elementId, elementType,
                true, appliedProfile, null, null);
    }

    public static ElementConfirmation error(String correlationId, String elementId,
                                            NetworkElement.ElementType elementType,
                                            String errorCode, String errorMessage) {
        return new ElementConfirmation(correlationId, elementId, elementType,
                false, null, errorCode, errorMessage);
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

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
                ? String.format("ElementConfirmation{OK, elementId='%s', type=%s, profile='%s'}",
                        elementId, elementType, appliedProfile)
                : String.format("ElementConfirmation{ERROR, elementId='%s', type=%s, code='%s'}",
                        elementId, elementType, errorCode);
    }
}
