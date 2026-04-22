package com.example.provisioning.workflow;

import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.model.ElementConfirmation;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow that orchestrates a SIM CSP (Customer Service Profile) change.
 *
 * After publishing provisioning commands to Kafka, the workflow pauses and
 * waits for one elementConfirmationReceived signal per NetworkElement in the
 * ProvisioningPlan. All elements must confirm before the workflow proceeds
 * to updateSimInventory.
 */
@WorkflowInterface
public interface CspChangeWorkflow {

    @WorkflowMethod
    CspChangeResult changeCsp(CspChangeRequest request);

    /**
     * Delivered once per NetworkElement by the HlrConfirmationDispatcher
     * after it consumes an ElementConfirmationMessage from the bus.
     * The workflow tracks received confirmations by elementId.
     */
    @SignalMethod
    void elementConfirmationReceived(ElementConfirmation confirmation);
}
