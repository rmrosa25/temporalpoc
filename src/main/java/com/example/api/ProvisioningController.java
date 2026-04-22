package com.example.api;

import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.model.ElementConfirmation;
import com.example.provisioning.workflow.CspChangeWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for the CSP change provisioning workflow.
 *
 * POST /api/provisioning/csp-change          — start a CspChangeWorkflow
 * POST /api/provisioning/{workflowId}/confirm — deliver one element confirmation signal
 *
 * Typical async flow:
 *   1. POST /api/provisioning/csp-change          → 202 { workflowId }
 *   2. Network element adapters call POST /confirm once per element
 *   3. Workflow unblocks, calls updateSimInventory, completes
 *
 * For manual testing without real network adapters, call /confirm three times
 * (once for HLR-EU-01, MSC-EU-03, SGSN-EU-07) from Postman or curl.
 */
@RestController
@RequestMapping("/api/provisioning")
public class ProvisioningController {

    private final WorkflowClient temporalClient;
    private final String taskQueue;

    public ProvisioningController(WorkflowClient temporalClient,
                                  @org.springframework.beans.factory.annotation.Value("${temporal.task-queue}")
                                  String taskQueue) {
        this.temporalClient = temporalClient;
        this.taskQueue = taskQueue;
    }

    // ── POST /api/provisioning/csp-change ─────────────────────────────────────

    @PostMapping("/csp-change")
    public ResponseEntity<WorkflowResponse> startCspChange(
            @RequestBody StartCspChangeRequest req,
            @RequestParam(defaultValue = "true") boolean async) {

        String correlationId = "CSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        CspChangeRequest request = new CspChangeRequest(
                correlationId,
                req.getIccid(),
                req.getMsisdn(),
                req.getNetworkId() != null ? req.getNetworkId() : "NET-EU-01",
                req.getCurrentCsp(),
                req.getTargetCsp(),
                req.getRequestedBy() != null ? req.getRequestedBy() : "api"
        );

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(correlationId)
                .setTaskQueue(taskQueue)
                .build();

        CspChangeWorkflow wf = temporalClient.newWorkflowStub(CspChangeWorkflow.class, opts);

        if (async) {
            WorkflowClient.start(wf::changeCsp, request);
            return ResponseEntity.accepted().body(WorkflowResponse.started(correlationId));
        }

        CspChangeResult result = wf.changeCsp(request);
        return ResponseEntity.ok(WorkflowResponse.completed(correlationId, result));
    }

    // ── POST /api/provisioning/{workflowId}/confirm ───────────────────────────

    /**
     * Delivers an elementConfirmationReceived signal to a running CspChangeWorkflow.
     * Call once per NetworkElement returned by the decompose activity.
     *
     * Example (success):
     *   POST /api/provisioning/CSP-ABC123/confirm
     *   {
     *     "elementId":      "HLR-EU-01",
     *     "elementType":    "HLR",
     *     "success":        true,
     *     "appliedProfile": "PROFILE_DATA_ROAMING"
     *   }
     *
     * Example (failure):
     *   {
     *     "elementId":    "MSC-EU-03",
     *     "elementType":  "MSC",
     *     "success":      false,
     *     "errorCode":    "ELEMENT_REJECTED",
     *     "errorMessage": "Profile not supported on this node"
     *   }
     */
    @PostMapping("/{workflowId}/confirm")
    public ResponseEntity<Void> confirmElement(
            @PathVariable String workflowId,
            @RequestBody ElementConfirmRequest req) {

        ElementConfirmation confirmation = req.isSuccess()
                ? ElementConfirmation.ok(
                        workflowId,
                        req.getElementId(),
                        req.getElementType(),
                        req.getAppliedProfile())
                : ElementConfirmation.error(
                        workflowId,
                        req.getElementId(),
                        req.getElementType(),
                        req.getErrorCode(),
                        req.getErrorMessage());

        CspChangeWorkflow wf = temporalClient.newWorkflowStub(CspChangeWorkflow.class, workflowId);
        wf.elementConfirmationReceived(confirmation);

        return ResponseEntity.accepted().build();
    }
}
