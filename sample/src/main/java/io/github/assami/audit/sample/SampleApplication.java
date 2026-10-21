package io.github.assami.audit.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample application demonstrating the audit-spring-boot-starter.
 *
 * <p>Run with the {@code dev} profile for an H2 in-memory database:</p>
 * <pre>{@code
 *   ./mvnw spring-boot:run -pl sample -Pdev
 * }</pre>
 *
 * <p>The audit API is available at http://localhost:8080/api/audit</p>
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
