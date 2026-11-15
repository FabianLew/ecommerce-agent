package com.ecommerceagent.automation.guardian;

/**
 * The ledger state an execution step returned to the workflow (ADR-0020 point 8). {@code executionId} is null when
 * nothing was authorized; {@code code} then says why.
 */
public record ExecutionRef(String executionId, String status, String code) {
}
