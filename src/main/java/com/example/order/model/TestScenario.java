package com.example.order.model;

/**
 * Describes a single test run: the order to submit, the expected outcome,
 * and which compensations should have executed.
 */
public class TestScenario {

    private final String name;
    private final Order order;
    private final String expectedStatus;   // "COMPLETED" or "FAILED"
    private final String description;
    private final String[] expectedCompensations; // log substrings to assert

    public TestScenario(String name, Order order, String expectedStatus,
                        String description, String... expectedCompensations) {
        this.name = name;
        this.order = order;
        this.expectedStatus = expectedStatus;
        this.description = description;
        this.expectedCompensations = expectedCompensations;
    }

    public String getName()                    { return name; }
    public Order getOrder()                    { return order; }
    public String getExpectedStatus()          { return expectedStatus; }
    public String getDescription()             { return description; }
    public String[] getExpectedCompensations() { return expectedCompensations; }
}
