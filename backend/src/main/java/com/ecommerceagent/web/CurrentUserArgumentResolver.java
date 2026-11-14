package com.ecommerceagent.web;

import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.UserDirectory;
import com.ecommerceagent.identity.UserIdentity;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link RegisteredUser} controller parameters from the verified JWT. */
@Component
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String REQUEST_ATTRIBUTE = CurrentUserArgumentResolver.class.getName();

    private final UserDirectory users;

    CurrentUserArgumentResolver(UserDirectory users) {
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return RegisteredUser.class.equals(parameter.getParameterType());
    }

    @Override
    public RegisteredUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return currentUser(request);
    }

    RegisteredUser currentUser(NativeWebRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (cached instanceof RegisteredUser user) {
            return user;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT principal");
        }
        RegisteredUser user = users.register(new UserIdentity(
                jwt.getIssuer().toString(),
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                displayName(jwt)));
        request.setAttribute(REQUEST_ATTRIBUTE, user, NativeWebRequest.SCOPE_REQUEST);
        return user;
    }

    private static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return name != null ? name : jwt.getClaimAsString("preferred_username");
    }
}
