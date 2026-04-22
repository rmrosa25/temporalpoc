package com.example.provisioning.workflow;

import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.model.HlrConfirmation;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow that orchestrates a SIM CSP (Customer Service Profile) change.
 *
 * The workflow pauses after publishing the HLR command and waits for an
 * external signal carrying the HLR confirmation. In production, a Kafka
 * consumer delivers this signal after consuming the HLR response topic.
 */
@WorkflowInterface
public interface CspChangeWorkflow {

    @WorkflowMethod
    CspChangeResult changeCsp(CspChangeRequest request);

    /**
     * Signal delivered by the Kafka consumer (or test stub) when the HLR
     * finishes processing the command. Unblocks the workflow.
     */
    @SignalMethod
    void hlrConfirmationReceived(HlrConfirmation confirmation);
}
