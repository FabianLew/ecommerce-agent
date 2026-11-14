package com.ecommerceagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single backend artifact, started with the {@code api} or {@code worker} profile.
 * Business modules are the direct subpackages, verified by Spring Modulith.
 */
@SpringBootApplication
public class OperatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperatorApplication.class, args);
    }
}
