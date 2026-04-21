package com.example.order.model;

import java.util.List;

public class BatchResult {

    private String batchId;
    private int total;
    private int completed;
    private int failed;
    private List<OrderResult> results;

    public BatchResult() {}

    public BatchResult(String batchId, int total, int completed, int failed, List<OrderResult> results) {
        this.batchId = batchId;
        this.total = total;
        this.completed = completed;
        this.failed = failed;
        this.results = results;
    }

    public String getBatchId()           { return batchId; }
    public void setBatchId(String v)     { this.batchId = v; }

    public int getTotal()                { return total; }
    public void setTotal(int v)          { this.total = v; }

    public int getCompleted()            { return completed; }
    public void setCompleted(int v)      { this.completed = v; }

    public int getFailed()               { return failed; }
    public void setFailed(int v)         { this.failed = v; }

    public List<OrderResult> getResults()          { return results; }
    public void setResults(List<OrderResult> v)    { this.results = v; }

    @Override
    public String toString() {
        return String.format("BatchResult{batchId='%s', total=%d, completed=%d, failed=%d}",
                batchId, total, completed, failed);
    }
}
