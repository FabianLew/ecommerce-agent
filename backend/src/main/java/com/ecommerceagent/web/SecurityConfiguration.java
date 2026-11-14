package com.ecommerceagent.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The API is an OAuth2 resource server: bearer JWTs from the OIDC provider (Keycloak locally).
 * Stateless, no cookies, so CSRF protection is not applicable. Store access is checked per request
 * by {@link StoreScopeArgumentResolver}, not by token claims (ADR-0004).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrorWriter errors) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            errors.write(request, response, 401, "UNAUTHENTICATED", "Sign in again to continue.");
        };
        AccessDeniedHandler forbidden = (request, response, exception) ->
                errors.write(request, response, 403, "FORBIDDEN", "You do not have permission for this operation.");
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
