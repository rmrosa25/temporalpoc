package com.example.order;

import com.example.order.model.BatchResult;
import com.example.order.model.FailureMode;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import com.example.order.workflow.BatchOrderWorkflow;
import com.example.order.workflow.FulfillmentWorkflow;
import com.example.order.workflow.OrderWorkflow;
import com.example.provisioning.kafka.HlrBusFactory;
import com.example.provisioning.kafka.KafkaSimulator;
import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.workflow.CspChangeWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one test scenario per invocation, selected by FAILURE_MODE env var.
 *
 * Order pipeline:
 *   NONE             → happy path
 *   INVALID_ORDER    → validation failure, no compensation
 *   PAYMENT_FAILURE  → saga: release inventory
 *   SHIPPING_FAILURE → saga: refund + release inventory
 *
 * Workflow patterns:
 *   PARENT_CHILD     → FulfillmentWorkflow with 2 sequential child workflows
 *   BATCH            → BatchOrderWorkflow fanning out 10 parallel children
 *
 * CSP change provisioning:
 *   CSP_HAPPY_PATH    → full success, HLR confirms via bus signal
 *   CSP_VALIDATE_FAIL → validation rejects before lock acquired
 *   CSP_HLR_ERROR     → HLR returns error via signal, saga unlocks SIM
 *   CSP_HLR_TIMEOUT   → no signal arrives, saga unlocks SIM
 *
 * Exits 0 on pass, 1 on fail.
 */
public class TestRunner {

    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[0;32m";
    private static final String RED    = "\033[0;31m";
    private static final String CYAN   = "\033[0;36m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BOLD   = "\033[1m";

    public static void main(String[] args) {
        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "localhost:7233");
        FailureMode mode = FailureMode.valueOf(
                System.getenv().getOrDefault("FAILURE_MODE", "NONE").toUpperCase());

        // Initialise the bus before connecting to Temporal so it's ready
        // when KafkaSimulator and the Worker's dispatcher both call HlrBusFactory.get().
        // For CSP scenarios the TestRunner and Worker share the same JVM, so the
        // singleton InProcessHlrBus is shared automatically.
        HlrBusFactory.get();

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(temporalHost).build()
        );
        WorkflowClient client = WorkflowClient.newInstance(service);

        printHeader(mode);

        boolean pass;
        switch (mode) {
            case PARENT_CHILD:
                pass = runParentChild(client);
                break;
            case BATCH:
                pass = runBatch(client);
                break;
            case CSP_HAPPY_PATH:
            case CSP_VALIDATE_FAIL:
            case CSP_HLR_ERROR:
            case CSP_HLR_TIMEOUT:
                pass = runCspChange(client, mode);
                break;
            default:
                pass = runSingleOrder(client, mode);
        }

        printResult(pass, mode);
        System.exit(pass ? 0 : 1);
    }

    // ── Order pipeline ────────────────────────────────────────────────────────

    private static boolean runSingleOrder(WorkflowClient client, FailureMode mode) {
        Order order = orderForMode(mode);
        String expectedStatus = (mode == FailureMode.NONE) ? "COMPLETED" : "FAILED";

        printScenarioHeader(labelFor(mode), descriptionFor(mode), expectedStatus);

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(order.getOrderId())
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();

        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, opts);
        OrderResult result = wf.processOrder(order);

        boolean pass = expectedStatus.equals(result.getStatus());
        if (pass) {
            log.info("{}{}[PASS]{} {} → status={}, message={}",
                    BOLD, GREEN, RESET, labelFor(mode), result.getStatus(), result.getMessage());
        } else {
            log.error("{}{}[FAIL]{} {} → expected={}, got={}, message={}",
                    BOLD, RED, RESET, labelFor(mode),
                    expectedStatus, result.getStatus(), result.getMessage());
        }
        return pass;
    }

    // ── Parent / Child ────────────────────────────────────────────────────────

    private static boolean runParentChild(WorkflowClient client) {
        String parentId = "FULFILL-" + shortId();
        Order primary   = new Order(newId(), "customer-10", "Laptop Pro X",     1, 2499.99);
        Order secondary = new Order(newId(), "customer-10", "Gift Wrap Add-on", 1,   19.99);

        String label = "Parent/Child Fulfillment";
        String desc  = "FulfillmentWorkflow (parent) spawns two child OrderWorkflows sequentially.\n" +
                       "   Child 1: primary item. Child 2: gift-wrap (only if primary succeeds).";
        printScenarioHeader(label, desc, "both children COMPLETED");

        log.info("Parent workflow ID: {}", parentId);

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(parentId)
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();

        FulfillmentWorkflow wf = client.newWorkflowStub(FulfillmentWorkflow.class, opts);
        List<OrderResult> results = wf.fulfill(primary, secondary);

        boolean pass = results.size() == 2
                && "COMPLETED".equals(results.get(0).getStatus())
                && "COMPLETED".equals(results.get(1).getStatus());

        for (int i = 0; i < results.size(); i++) {
            OrderResult r = results.get(i);
            log.info("  Child {} ({}) → status={}, message={}",
                    i + 1, i == 0 ? "primary" : "secondary", r.getStatus(), r.getMessage());
        }
        if (pass) {
            log.info("{}{}[PASS]{} {} → both children completed", BOLD, GREEN, RESET, label);
        } else {
            log.error("{}{}[FAIL]{} {} → unexpected child status", BOLD, RED, RESET, label);
        }
        return pass;
    }

    // ── Batch ─────────────────────────────────────────────────────────────────

    private static boolean runBatch(WorkflowClient client) {
        String batchId = "BATCH-" + shortId();
        int batchSize  = 10;

        String label = "Batch Fan-out (" + batchSize + " parallel child workflows)";
        String desc  = "BatchOrderWorkflow fans out " + batchSize + " child OrderWorkflows in parallel\n" +
                       "   using Async.function() + Promise.allOf(). Aggregates results.";
        printScenarioHeader(label, desc, "all " + batchSize + " children COMPLETED");

        String[] items = {
            "Laptop Pro X", "Wireless Headphones", "Mechanical Keyboard",
            "USB-C Hub",    "Webcam HD",           "Monitor Stand",
            "Mouse Pad XL", "LED Desk Lamp",       "Cable Organizer",
            "Laptop Sleeve"
        };
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            orders.add(new Order(newId(), "customer-" + String.format("%02d", i + 1),
                    items[i], 1, Math.round((29.99 + i * 15.5) * 100.0) / 100.0));
        }

        log.info("Batch ID: {} — spawning {} child workflows in parallel", batchId, batchSize);

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(batchId)
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();

        BatchOrderWorkflow wf = client.newWorkflowStub(BatchOrderWorkflow.class, opts);
        BatchResult result = wf.processBatch(orders);

        boolean pass = result.getCompleted() == batchSize && result.getFailed() == 0;
        log.info("Batch result: total={}, completed={}, failed={}",
                result.getTotal(), result.getCompleted(), result.getFailed());
        result.getResults().forEach(r ->
                log.info("  {} → status={}", r.getOrderId(), r.getStatus()));

        if (pass) {
            log.info("{}{}[PASS]{} {} → {}/{} completed",
                    BOLD, GREEN, RESET, label, result.getCompleted(), batchSize);
        } else {
            log.error("{}{}[FAIL]{} {} → only {}/{} completed",
                    BOLD, RED, RESET, label, result.getCompleted(), batchSize);
        }
        return pass;
    }

    // ── CSP Change provisioning ───────────────────────────────────────────────

    private static boolean runCspChange(WorkflowClient client, FailureMode mode) {
        String correlationId = "CSP-" + shortId();
        CspChangeRequest request = new CspChangeRequest(
                correlationId,
                "8931080019" + shortId().substring(0, 6),  // ICCID
                "+1555" + shortId().substring(0, 7),        // MSISDN
                "NET-EU-01",                                // networkId
                "PROFILE_BASIC",
                "PROFILE_DATA_ROAMING",
                "operator-portal"
        );

        String label    = cspLabelFor(mode);
        String expected = (mode == FailureMode.CSP_HAPPY_PATH) ? "COMPLETED" : "FAILED or TIMED_OUT";

        printScenarioHeader(label, cspDescriptionFor(mode), expected);
        log.info("correlationId={}, iccid={}, {}→{}",
                request.getCorrelationId(), request.getIccid(),
                request.getCurrentCsp(), request.getTargetCsp());

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(correlationId)
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();

        CspChangeWorkflow wfStub = client.newWorkflowStub(CspChangeWorkflow.class, opts);

        // CSP_VALIDATE_FAIL: activity throws synchronously — no bus involved.
        if (mode == FailureMode.CSP_VALIDATE_FAIL) {
            CspChangeResult result = wfStub.changeCsp(request);
            boolean pass = result.getStatus() == CspChangeResult.Status.FAILED;
            logCspResult(pass, label, result);
            return pass;
        }

        // CSP_HLR_TIMEOUT: simulator consumes the command but publishes nothing.
        if (mode == FailureMode.CSP_HLR_TIMEOUT) {
            log.info("[KafkaSimulator] TIMEOUT mode — will consume command but not respond");
            KafkaSimulator simulator = new KafkaSimulator(KafkaSimulator.Mode.TIMEOUT, 500);
            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.submit(simulator);

            CspChangeResult result = wfStub.changeCsp(request);
            simulator.close();
            exec.shutdownNow();

            boolean pass = result.getStatus() == CspChangeResult.Status.TIMED_OUT;
            logCspResult(pass, label, result);
            return pass;
        }

        // CSP_HAPPY_PATH / CSP_HLR_ERROR: start simulator, then workflow async.
        KafkaSimulator.Mode simMode = (mode == FailureMode.CSP_HAPPY_PATH)
                ? KafkaSimulator.Mode.SUCCESS
                : KafkaSimulator.Mode.ERROR;

        log.info("[KafkaSimulator] Starting in {} mode", simMode);
        KafkaSimulator simulator = new KafkaSimulator(simMode, 1500);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(simulator);

        CompletableFuture<CspChangeResult> future = WorkflowClient.execute(wfStub::changeCsp, request);

        try {
            CspChangeResult result = future.get();
            boolean pass = (mode == FailureMode.CSP_HAPPY_PATH)
                    ? result.getStatus() == CspChangeResult.Status.COMPLETED
                    : result.getStatus() == CspChangeResult.Status.FAILED;
            logCspResult(pass, label, result);
            return pass;
        } catch (Exception e) {
            log.error("{}{}[FAIL]{} {} → exception: {}", BOLD, RED, RESET, label, e.getMessage());
            return false;
        } finally {
            simulator.close();
            exec.shutdownNow();
        }
    }

    private static void logCspResult(boolean pass, String label, CspChangeResult result) {
        if (pass) {
            log.info("{}{}[PASS]{} {} → status={}, detail={}",
                    BOLD, GREEN, RESET, label, result.getStatus(), result);
        } else {
            log.error("{}{}[FAIL]{} {} → status={}, detail={}",
                    BOLD, RED, RESET, label, result.getStatus(), result);
        }
    }

    // ── CSP label / description helpers ──────────────────────────────────────

    private static String cspLabelFor(FailureMode mode) {
        switch (mode) {
            case CSP_HAPPY_PATH:    return "CSP Change — Happy Path";
            case CSP_VALIDATE_FAIL: return "CSP Change — Validation Failure";
            case CSP_HLR_ERROR:     return "CSP Change — HLR Error (saga: unlock SIM)";
            case CSP_HLR_TIMEOUT:   return "CSP Change — HLR Timeout (saga: unlock SIM)";
            default:                return mode.name();
        }
    }

    private static String cspDescriptionFor(FailureMode mode) {
        switch (mode) {
            case CSP_HAPPY_PATH:
                return "Full pipeline: validate → lock SIM → publish HLR command →\n" +
                       "   receive HLR success signal → update SIM Inventory.";
            case CSP_VALIDATE_FAIL:
                return "Validation rejects the request (SIM not found / CSP mismatch).\n" +
                       "   No lock acquired, no bus message published.";
            case CSP_HLR_ERROR:
                return "HLR returns an explicit error via signal after command is published.\n" +
                       "   Saga compensates: unlocks SIM. Inventory not updated.";
            case CSP_HLR_TIMEOUT:
                return "No HLR confirmation signal arrives within the timeout window.\n" +
                       "   Saga compensates: unlocks SIM. Inventory not updated.";
            default:
                return mode.name();
        }
    }

    // ── Order helpers ─────────────────────────────────────────────────────────

    private static Order orderForMode(FailureMode mode) {
        switch (mode) {
            case NONE:             return new Order(newId(), "customer-01", "Laptop Pro X",       2, 2499.99);
            case INVALID_ORDER:    return new Order(newId(), "customer-02", "Unknown Gadget Z",    1,   99.99);
            case PAYMENT_FAILURE:  return new Order(newId(), "customer-03", "Wireless Headphones", 1,  349.99);
            case SHIPPING_FAILURE: return new Order(newId(), "customer-04", "Mechanical Keyboard", 3,  189.99);
            default: throw new IllegalArgumentException("No order mapping for mode: " + mode);
        }
    }

    private static String labelFor(FailureMode mode) {
        switch (mode) {
            case NONE:             return "Happy Path";
            case INVALID_ORDER:    return "Invalid Order (catalog rejection)";
            case PAYMENT_FAILURE:  return "Payment Failure (saga: release inventory)";
            case SHIPPING_FAILURE: return "Shipping Failure (saga: refund + release inventory)";
            default:               return mode.name();
        }
    }

    private static String descriptionFor(FailureMode mode) {
        switch (mode) {
            case NONE:
                return "All activities succeed. Full pipeline: validate → reserve → charge → ship → email.";
            case INVALID_ORDER:
                return "Worker rejects item at validation (not in catalog).\n" +
                       "   No inventory reserved, no saga compensation needed.";
            case PAYMENT_FAILURE:
                return "Payment gateway rejects charge after inventory is reserved.\n" +
                       "   Saga runs LIFO: releases inventory reservation.";
            case SHIPPING_FAILURE:
                return "Shipping provider unavailable after payment is charged.\n" +
                       "   Saga runs LIFO: refunds payment, then releases inventory.";
            default:
                return mode.name();
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private static void printHeader(FailureMode mode) {
        System.out.println();
        System.out.println(CYAN + BOLD +
                "╔══════════════════════════════════════════════════════╗" + RESET);
        System.out.printf("%s%s║   Temporal Order POC — Scenario: %-19s║%s%n",
                CYAN, BOLD, mode, RESET);
        System.out.println(CYAN + BOLD +
                "╚══════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    private static void printScenarioHeader(String label, String desc, String expected) {
        System.out.printf("%s%s── %s%s%n", BOLD, YELLOW, label, RESET);
        System.out.println("   " + desc);
        System.out.printf("   Expected: %s%s%s%n%n", BOLD, expected, RESET);
    }

    private static void printResult(boolean pass, FailureMode mode) {
        System.out.println();
        if (pass) {
            System.out.println(GREEN + BOLD + "   ✓ PASS — " + mode + RESET);
        } else {
            System.out.println(RED + BOLD + "   ✗ FAIL — " + mode + RESET);
        }
        System.out.println();
    }

    private static String newId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
