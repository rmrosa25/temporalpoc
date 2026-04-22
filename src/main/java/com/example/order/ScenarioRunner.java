package com.example.order;

import com.example.order.activity.OrderActivitiesImpl;
import com.example.order.model.BatchResult;
import com.example.order.model.FailureMode;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import com.example.order.workflow.BatchOrderWorkflow;
import com.example.order.workflow.BatchOrderWorkflowImpl;
import com.example.order.workflow.FulfillmentWorkflow;
import com.example.order.workflow.FulfillmentWorkflowImpl;
import com.example.order.workflow.OrderWorkflow;
import com.example.order.workflow.OrderWorkflowImpl;
import com.example.provisioning.activity.CspChangeActivities;
import com.example.provisioning.activity.CspChangeActivitiesImpl;
import com.example.provisioning.kafka.HlrBusFactory;
import com.example.provisioning.kafka.HlrConfirmationDispatcher;
import com.example.provisioning.kafka.KafkaSimulator;
import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.workflow.CspChangeWorkflow;
import com.example.provisioning.workflow.CspChangeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-JVM runner: starts the Temporal worker, runs one scenario, then exits.
 *
 * By running worker and test in the same JVM, the InProcessHlrBus singleton
 * is shared between CspChangeActivitiesImpl, HlrConfirmationDispatcher,
 * and KafkaSimulator — no external Kafka or Docker required.
 *
 * Usage (via run.sh):
 *   java -cp <jar> com.example.order.ScenarioRunner <FAILURE_MODE>
 *
 * Exits 0 on pass, 1 on fail.
 */
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[0;32m";
    private static final String RED    = "\033[0;31m";
    private static final String CYAN   = "\033[0;36m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BOLD   = "\033[1m";

    public static void main(String[] args) throws Exception {
        FailureMode mode = FailureMode.valueOf(
                System.getenv().getOrDefault("FAILURE_MODE", "NONE").toUpperCase());

        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "localhost:7233");

        // ── 1. Initialise the bus (in-process or Kafka) ───────────────────────
        HlrBusFactory.get();

        // ── 2. Connect to Temporal ────────────────────────────────────────────
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(temporalHost).build()
        );
        WorkflowClient client = WorkflowClient.newInstance(service);

        // ── 3. Start worker ───────────────────────────────────────────────────
        WorkerFactory factory = WorkerFactory.newInstance(client);
        io.temporal.worker.Worker worker = factory.newWorker(Worker.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                OrderWorkflowImpl.class,
                FulfillmentWorkflowImpl.class,
                BatchOrderWorkflowImpl.class,
                CspChangeWorkflowImpl.class
        );
        worker.registerActivitiesImplementations(
                new OrderActivitiesImpl(),
                new CspChangeActivitiesImpl()
        );

        // ── 4. Start HLR confirmation dispatcher ──────────────────────────────
        HlrConfirmationDispatcher dispatcher = new HlrConfirmationDispatcher(
                HlrBusFactory.get(), client);
        Thread dispatcherThread = new Thread(dispatcher, "hlr-confirmation-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        factory.start();
        log.info("Worker started in-process | FAILURE_MODE={}", mode);

        // ── 5. Run scenario ───────────────────────────────────────────────────
        printHeader(mode);
        boolean pass;
        try {
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
        } finally {
            dispatcher.close();
            factory.shutdown();
            HlrBusFactory.close();
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
            log.info("{}{}[PASS]{} {} → status={}", BOLD, GREEN, RESET, labelFor(mode), result.getStatus());
        } else {
            log.error("{}{}[FAIL]{} {} → expected={}, got={}", BOLD, RED, RESET,
                    labelFor(mode), expectedStatus, result.getStatus());
        }
        return pass;
    }

    // ── Parent / Child ────────────────────────────────────────────────────────

    private static boolean runParentChild(WorkflowClient client) {
        String parentId = "FULFILL-" + shortId();
        Order primary   = new Order(newId(), "customer-10", "Laptop Pro X",     1, 2499.99);
        Order secondary = new Order(newId(), "customer-10", "Gift Wrap Add-on", 1,   19.99);

        printScenarioHeader("Parent/Child Fulfillment",
                "FulfillmentWorkflow spawns two child OrderWorkflows sequentially.",
                "both children COMPLETED");

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(parentId).setTaskQueue(Worker.TASK_QUEUE).build();

        FulfillmentWorkflow wf = client.newWorkflowStub(FulfillmentWorkflow.class, opts);
        List<OrderResult> results = wf.fulfill(primary, secondary);

        boolean pass = results.size() == 2
                && "COMPLETED".equals(results.get(0).getStatus())
                && "COMPLETED".equals(results.get(1).getStatus());

        if (pass) log.info("{}{}[PASS]{} Parent/Child → both children completed", BOLD, GREEN, RESET);
        else       log.error("{}{}[FAIL]{} Parent/Child → unexpected child status", BOLD, RED, RESET);
        return pass;
    }

    // ── Batch ─────────────────────────────────────────────────────────────────

    private static boolean runBatch(WorkflowClient client) {
        String batchId = "BATCH-" + shortId();
        int batchSize  = 10;

        printScenarioHeader("Batch Fan-out (" + batchSize + " parallel children)",
                "BatchOrderWorkflow fans out " + batchSize + " child OrderWorkflows via Async.function().",
                "all " + batchSize + " children COMPLETED");

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

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(batchId).setTaskQueue(Worker.TASK_QUEUE).build();

        BatchOrderWorkflow wf = client.newWorkflowStub(BatchOrderWorkflow.class, opts);
        BatchResult result = wf.processBatch(orders);

        boolean pass = result.getCompleted() == batchSize && result.getFailed() == 0;
        log.info("Batch: total={}, completed={}, failed={}",
                result.getTotal(), result.getCompleted(), result.getFailed());

        if (pass) log.info("{}{}[PASS]{} Batch → {}/{} completed", BOLD, GREEN, RESET, result.getCompleted(), batchSize);
        else       log.error("{}{}[FAIL]{} Batch → only {}/{} completed", BOLD, RED, RESET, result.getCompleted(), batchSize);
        return pass;
    }

    // ── CSP Change provisioning ───────────────────────────────────────────────

    private static boolean runCspChange(WorkflowClient client, FailureMode mode) {
        String correlationId = "CSP-" + shortId();
        CspChangeRequest request = new CspChangeRequest(
                correlationId,
                "8931080019" + shortId().substring(0, 6),
                "+1555" + shortId().substring(0, 7),
                "NET-EU-01",
                "PROFILE_BASIC",
                "PROFILE_DATA_ROAMING",
                "operator-portal"
        );

        printScenarioHeader(cspLabelFor(mode), cspDescriptionFor(mode),
                mode == FailureMode.CSP_HAPPY_PATH ? "COMPLETED" : "FAILED or TIMED_OUT");

        log.info("correlationId={}, iccid={}", request.getCorrelationId(), request.getIccid());

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(correlationId).setTaskQueue(Worker.TASK_QUEUE).build();

        CspChangeWorkflow wfStub = client.newWorkflowStub(CspChangeWorkflow.class, opts);

        if (mode == FailureMode.CSP_VALIDATE_FAIL) {
            CspChangeResult result = wfStub.changeCsp(request);
            boolean pass = result.getStatus() == CspChangeResult.Status.FAILED;
            logCspResult(pass, cspLabelFor(mode), result);
            return pass;
        }

        if (mode == FailureMode.CSP_HLR_TIMEOUT) {
            KafkaSimulator simulator = new KafkaSimulator(KafkaSimulator.Mode.TIMEOUT, 500);
            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.submit(simulator);
            try {
                CspChangeResult result = wfStub.changeCsp(request);
                boolean pass = result.getStatus() == CspChangeResult.Status.TIMED_OUT;
                logCspResult(pass, cspLabelFor(mode), result);
                return pass;
            } finally {
                simulator.close();
                exec.shutdownNow();
            }
        }

        KafkaSimulator.Mode simMode = (mode == FailureMode.CSP_HAPPY_PATH)
                ? KafkaSimulator.Mode.SUCCESS : KafkaSimulator.Mode.ERROR;

        KafkaSimulator simulator = new KafkaSimulator(simMode, 1500);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(simulator);

        CompletableFuture<CspChangeResult> future = WorkflowClient.execute(wfStub::changeCsp, request);
        try {
            CspChangeResult result = future.get();
            boolean pass = (mode == FailureMode.CSP_HAPPY_PATH)
                    ? result.getStatus() == CspChangeResult.Status.COMPLETED
                    : result.getStatus() == CspChangeResult.Status.FAILED;
            logCspResult(pass, cspLabelFor(mode), result);
            return pass;
        } catch (Exception e) {
            log.error("{}{}[FAIL]{} {} → exception: {}", BOLD, RED, RESET, cspLabelFor(mode), e.getMessage());
            return false;
        } finally {
            simulator.close();
            exec.shutdownNow();
        }
    }

    private static void logCspResult(boolean pass, String label, CspChangeResult result) {
        if (pass) log.info("{}{}[PASS]{} {} → status={}", BOLD, GREEN, RESET, label, result.getStatus());
        else       log.error("{}{}[FAIL]{} {} → status={}", BOLD, RED, RESET, label, result.getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String cspLabelFor(FailureMode mode) {
        switch (mode) {
            case CSP_HAPPY_PATH:    return "CSP Change — Happy Path";
            case CSP_VALIDATE_FAIL: return "CSP Change — Validation Failure";
            case CSP_HLR_ERROR:     return "CSP Change — HLR Error";
            case CSP_HLR_TIMEOUT:   return "CSP Change — HLR Timeout";
            default:                return mode.name();
        }
    }

    private static String cspDescriptionFor(FailureMode mode) {
        switch (mode) {
            case CSP_HAPPY_PATH:    return "validate → lockSim → decompose → provisioningCommand (HLR+MSC+SGSN) → all 3 signals → updateSimInventory";
            case CSP_VALIDATE_FAIL: return "Validation Service rejects request. No lock, no decompose, no commands.";
            case CSP_HLR_ERROR:     return "Network elements return error signals. Saga: rollbackLock.";
            case CSP_HLR_TIMEOUT:   return "No element confirmations within timeout. Saga: rollbackLock.";
            default:                return mode.name();
        }
    }

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
            case INVALID_ORDER:    return "Invalid Order";
            case PAYMENT_FAILURE:  return "Payment Failure (saga: release inventory)";
            case SHIPPING_FAILURE: return "Shipping Failure (saga: refund + release inventory)";
            default:               return mode.name();
        }
    }

    private static String descriptionFor(FailureMode mode) {
        switch (mode) {
            case NONE:             return "All activities succeed. Full pipeline.";
            case INVALID_ORDER:    return "Validation rejects item (not in catalog).";
            case PAYMENT_FAILURE:  return "Payment fails after inventory reserved. Saga: release inventory.";
            case SHIPPING_FAILURE: return "Shipping fails after payment. Saga: refund + release inventory.";
            default:               return mode.name();
        }
    }

    private static void printHeader(FailureMode mode) {
        System.out.println();
        System.out.println(CYAN + BOLD + "╔══════════════════════════════════════════════════════╗" + RESET);
        System.out.printf("%s%s║   Temporal Order POC — Scenario: %-19s║%s%n", CYAN, BOLD, mode, RESET);
        System.out.println(CYAN + BOLD + "╚══════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    private static void printScenarioHeader(String label, String desc, String expected) {
        System.out.printf("%s%s── %s%s%n", BOLD, YELLOW, label, RESET);
        System.out.println("   " + desc);
        System.out.printf("   Expected: %s%s%s%n%n", BOLD, expected, RESET);
    }

    private static void printResult(boolean pass, FailureMode mode) {
        System.out.println();
        if (pass) System.out.println(GREEN + BOLD + "   ✓ PASS — " + mode + RESET);
        else       System.out.println(RED   + BOLD + "   ✗ FAIL — " + mode + RESET);
        System.out.println();
    }

    private static String newId()   { return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }
}
