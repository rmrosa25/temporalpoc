package com.example.order;

import com.example.order.model.FailureMode;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import com.example.order.model.TestScenario;
import com.example.order.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs 4 test scenarios against a live Temporal server, one per FAILURE_MODE.
 *
 * Each scenario is run with a dedicated worker configured for that mode,
 * so only the targeted activity fails. The process exits with code 1 if
 * any scenario produces an unexpected result.
 *
 * Scenarios:
 *   NONE             → Happy path: full pipeline succeeds
 *   INVALID_ORDER    → Validation rejects order before any mutation; no compensation
 *   PAYMENT_FAILURE  → Payment fails after inventory reserved; saga releases inventory
 *   SHIPPING_FAILURE → Shipping fails after payment charged; saga refunds + releases
 *
 * FAILURE_MODE env var controls both which scenario to run AND which activity
 * the worker will fail — they must match (enforced by run.sh / run-macos.sh).
 */
public class TestRunner {

    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[0;32m";
    private static final String RED    = "\033[0;31m";
    private static final String CYAN   = "\033[0;36m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BOLD   = "\033[1m";

    public static void main(String[] args) throws InterruptedException {
        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "localhost:7233");
        FailureMode failureMode = FailureMode.valueOf(
                System.getenv().getOrDefault("FAILURE_MODE", "NONE").toUpperCase());

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build()
        );
        WorkflowClient client = WorkflowClient.newInstance(service);

        // Each mode runs exactly the scenario designed for it
        TestScenario scenario = buildScenario(failureMode);

        printHeader(failureMode);
        printScenarioHeader(scenario);

        OrderResult result = runScenario(client, scenario);
        boolean pass = scenario.getExpectedStatus().equals(result.getStatus());

        if (pass) {
            log.info("{}{}[PASS]{} {} → status={}, message={}",
                    BOLD, GREEN, RESET, scenario.getName(),
                    result.getStatus(), result.getMessage());
        } else {
            log.error("{}{}[FAIL]{} {} → expected={}, got={}, message={}",
                    BOLD, RED, RESET, scenario.getName(),
                    scenario.getExpectedStatus(), result.getStatus(), result.getMessage());
        }

        printResult(pass, scenario);
        System.exit(pass ? 0 : 1);
    }

    private static TestScenario buildScenario(FailureMode mode) {
        switch (mode) {

            case NONE:
                return new TestScenario(
                        "Happy Path",
                        new Order(newId(), "customer-01", "Laptop Pro X", 2, 2499.99),
                        "COMPLETED",
                        "All activities succeed. Order is validated, inventory reserved,\n" +
                        "   payment charged, order shipped, confirmation email sent.",
                        new String[0]
                );

            case INVALID_ORDER:
                // Structurally valid order, but worker rejects it at validation
                // (item not in catalog). No inventory reserved → no compensation.
                return new TestScenario(
                        "Invalid Order (catalog rejection)",
                        new Order(newId(), "customer-02", "Unknown Gadget Z", 1, 99.99),
                        "FAILED",
                        "Worker rejects the item at validation (not in catalog).\n" +
                        "   No inventory reserved, no saga compensation needed.",
                        new String[0]
                );

            case PAYMENT_FAILURE:
                return new TestScenario(
                        "Payment Failure (saga: release inventory)",
                        new Order(newId(), "customer-03", "Wireless Headphones", 1, 349.99),
                        "FAILED",
                        "Payment gateway rejects the charge after inventory is reserved.\n" +
                        "   Saga runs LIFO: releases inventory reservation.",
                        "Releasing inventory reservation"
                );

            case SHIPPING_FAILURE:
                return new TestScenario(
                        "Shipping Failure (saga: refund + release inventory)",
                        new Order(newId(), "customer-04", "Mechanical Keyboard", 3, 189.99),
                        "FAILED",
                        "Shipping provider unavailable after payment is charged.\n" +
                        "   Saga runs LIFO: refunds payment, then releases inventory.",
                        "Refunding charge", "Releasing inventory reservation"
                );

            default:
                throw new IllegalArgumentException("Unknown failure mode: " + mode);
        }
    }

    private static OrderResult runScenario(WorkflowClient client, TestScenario scenario) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(scenario.getOrder().getOrderId())
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();
        OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);
        return workflow.processOrder(scenario.getOrder());
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

    private static void printScenarioHeader(TestScenario scenario) {
        System.out.printf("%s%s── %s%s%n", BOLD, YELLOW, scenario.getName(), RESET);
        System.out.println("   " + scenario.getDescription());
        System.out.printf("   Expected: %s%s%s%n%n", BOLD, scenario.getExpectedStatus(), RESET);
    }

    private static void printResult(boolean pass, TestScenario scenario) {
        System.out.println();
        if (pass) {
            System.out.println(GREEN + BOLD + "   ✓ PASS — " + scenario.getName() + RESET);
        } else {
            System.out.println(RED + BOLD + "   ✗ FAIL — " + scenario.getName() + RESET);
        }
        System.out.println();
    }

    private static String newId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
