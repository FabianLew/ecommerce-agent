package com.ecommerceagent.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the JSON answer of an idempotent command that claimed its key, so a repeat gets the same answer. */
@ControllerAdvice
class IdempotentResponseAdvice implements ResponseBodyAdvice<Object> {

    private final JsonMapper json;

    IdempotentResponseAdvice(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        RateLimited limited = returnType.getMethodAnnotation(RateLimited.class);
        return limited != null && limited.idempotent();
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet) {
            HttpServletRequest http = servlet.getServletRequest();
            if (http.getAttribute(ApiRateLimitInterceptor.CLAIM) != null && body != null) {
                String text = json.writeValueAsString(body);
                if (text.length() <= 65_536) {
                    http.setAttribute(ApiRateLimitInterceptor.BODY, text);
                }
            }
        }
        return body;
    }
}
