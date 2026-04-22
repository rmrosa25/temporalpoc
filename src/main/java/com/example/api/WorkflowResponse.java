package com.example.api;

/**
 * Generic response returned when a workflow is started asynchronously.
 * The caller can use workflowId to query status or send signals via the
 * Temporal UI or the /api/workflows/{workflowId}/status endpoint.
 */
public class WorkflowResponse {

    private String workflowId;
    private String status;   // STARTED | COMPLETED | FAILED
    private Object result;   // populated when async=false (sync execution)

    public static WorkflowResponse started(String workflowId) {
        WorkflowResponse r = new WorkflowResponse();
        r.workflowId = workflowId;
        r.status = "STARTED";
        return r;
    }

    public static WorkflowResponse completed(String workflowId, Object result) {
        WorkflowResponse r = new WorkflowResponse();
        r.workflowId = workflowId;
        r.status = "COMPLETED";
        r.result = result;
        return r;
    }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String v) { this.workflowId = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public Object getResult() { return result; }
    public void setResult(Object v) { this.result = v; }
}
