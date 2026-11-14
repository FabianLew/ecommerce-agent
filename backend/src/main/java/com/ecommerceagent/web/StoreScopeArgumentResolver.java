package com.ecommerceagent.web;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Builds {@link TrustedStoreScope} for endpoints under {@code /api/v1/stores/{storeId}} from the
 * authenticated user and a verified membership. Unknown, malformed and foreign store IDs all end in
 * the same 404, and denials are audited (ADR-0004).
 */
@Component
class StoreScopeArgumentResolver implements HandlerMethodArgumentResolver {

    static final String STORE_ID_VARIABLE = "storeId";

    private final CurrentUserArgumentResolver currentUser;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;

    StoreScopeArgumentResolver(CurrentUserArgumentResolver currentUser, StoreAccess storeAccess, AuditTrail audit) {
        this.currentUser = currentUser;
        this.storeAccess = storeAccess;
        this.audit = audit;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TrustedStoreScope.class.equals(parameter.getParameterType());
    }

    @Override
    public TrustedStoreScope resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        RegisteredUser user = currentUser.currentUser(request);
        String rawStoreId = pathVariable(request);
        Optional<TrustedStoreScope> scope = parseUuid(rawStoreId)
                .flatMap(storeId -> storeAccess.scopeFor(user.id(), storeId));
        if (scope.isEmpty()) {
            audit.record(new AuditEntry("STORE_ACCESS_DENIED", null, null, user.id(), "store",
                    truncate(rawStoreId), Map.of()));
            throw new ResourceNotFoundException();
        }
        return scope.get();
    }

    @SuppressWarnings("unchecked")
    private static String pathVariable(NativeWebRequest request) {
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        String value = variables == null ? null : variables.get(STORE_ID_VARIABLE);
        if (value == null) {
            throw new IllegalStateException("TrustedStoreScope requires a {storeId} path variable");
        }
        return value;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String truncate(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
