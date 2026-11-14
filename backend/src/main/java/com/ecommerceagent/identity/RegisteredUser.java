package com.ecommerceagent.identity;

import java.util.UUID;

public record RegisteredUser(UUID id, String email, String displayName) {
}
