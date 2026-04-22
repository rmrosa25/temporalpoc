package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Temporal POC Spring Boot application.
 *
 * Starts:
 *   - Embedded Tomcat on port 8080 (REST API)
 *   - Temporal worker polling "order-processing" task queue
 *   - HlrConfirmationDispatcher background thread
 *
 * See com.example.config.TemporalConfig for worker wiring.
 * See com.example.api for REST endpoints.
 */
@SpringBootApplication
public class TemporalPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemporalPocApplication.class, args);
    }
}
