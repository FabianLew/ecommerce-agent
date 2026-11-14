package com.ecommerceagent.decisions;

/** One reason a proposal is invalid: a stable code, the JSON path it concerns and a message for logs and audit. */
public record ProposalError(String code, String path, String message) {
}
