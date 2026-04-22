package com.example.api;

import com.example.provisioning.model.NetworkElement;

/**
 * POST /api/provisioning/{workflowId}/confirm
 *
 * Delivers an elementConfirmationReceived signal to a waiting CspChangeWorkflow.
 * Send one request per NetworkElement in the provisioning plan.
 */
public class ElementConfirmRequest {

    private String elementId;
    private NetworkElement.ElementType elementType;
    private boolean success;
    private String appliedProfile; // required when success=true
    private String errorCode;      // required when success=false
    private String errorMessage;   // required when success=false

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
}
