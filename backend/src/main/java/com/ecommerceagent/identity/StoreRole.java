package com.ecommerceagent.identity;

/**
 * Working store-scoped roles from document 04 §11 [P]. The permission matrix is decided in O-13;
 * until then access checks only require a membership (ADR-0004).
 */
public enum StoreRole {
    OWNER,
    DECISION_OPERATOR,
    SUPPORT_OPERATOR,
    ANALYST
}
