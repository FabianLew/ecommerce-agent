package com.ecommerceagent.web;

import java.util.List;
import java.util.Map;

import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI contract of {@code /api/v1}, the source of the generated frontend client (B-09, ADR-0006).
 * Server-resolved parameters (current user, store scope) are hidden; the {@code storeId} path variable
 * and the 04 §3 error responses are documented here once instead of on every endpoint.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(TrustedStoreScope.class, RegisteredUser.class);
    }

    private static final String ERROR_REF = "#/components/schemas/ApiError";

    @Bean
    OpenApiCustomizer operatorApiContract() {
        return openApi -> {
            openApi.info(new Info().title("Autonomous E-commerce Operator API").version("v1"))
                    .servers(List.of(new Server().url("/")))
                    .addSecurityItem(new SecurityRequirement().addList("bearer"));
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            components.addSecuritySchemes("bearer",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
            @SuppressWarnings("rawtypes")
            Map<String, Schema> errorSchemas = ModelConverters.getInstance().readAll(ApiError.class);
            errorSchemas.forEach(components::addSchemas);
            openApi.components(components);

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                addError(operation, "401", "Missing or invalid access token");
                if (path.contains("{" + StoreScopeArgumentResolver.STORE_ID_VARIABLE + "}")) {
                    addStoreIdParameter(operation);
                    addError(operation, "403", "Store member without the required permission");
                    addError(operation, "404", "Store or resource does not exist or is not available");
                }
            }));
        };
    }

    private static void addStoreIdParameter(Operation operation) {
        boolean present = operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(p -> StoreScopeArgumentResolver.STORE_ID_VARIABLE.equals(p.getName()));
        if (!present) {
            operation.addParametersItem(new PathParameter()
                    .name(StoreScopeArgumentResolver.STORE_ID_VARIABLE)
                    .required(true)
                    .schema(new StringSchema().format("uuid")));
        }
    }

    private static void addError(Operation operation, String status, String description) {
        operation.getResponses().putIfAbsent(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref(ERROR_REF)))));
    }
}
