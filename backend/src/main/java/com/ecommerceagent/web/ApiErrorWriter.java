package com.ecommerceagent.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@link ApiError} bodies outside Spring MVC (security filter chain). */
@Component
class ApiErrorWriter {

    private final JsonMapper json;

    ApiErrorWriter(JsonMapper json) {
        this.json = json;
    }

    void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                ApiError.of(code, message, false, RequestCorrelationFilter.requestId(request)));
    }
}
