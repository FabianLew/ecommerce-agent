package com.ecommerceagent.intelligence.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * B-26 contract of the Spring AI adapter against a local stub of the Messages API: it returns canonical turns,
 * never executes a tool (ADR-0002), sends tool results back by id, and types failures. The base URL is explicit, so
 * the test never reaches a real endpoint.
 */
class SpringAiAnthropicGatewayTest {

    static final ModelId MODEL = new ModelId("anthropic", "claude-stub");
    static final ToolSpec FORECAST = new ToolSpec("get_inventory_forecast", "Forecast of the variant.",
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"productVariantId\"],"
                    + "\"properties\":{\"productVariantId\":{\"type\":\"string\"}}}");

    static AnthropicStubServer stub;
    SpringAiAnthropicGateway gateway;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new AnthropicStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void gateway() {
        stub.reset();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder().apiKey("test-key").baseUrl(stub.baseUrl()).maxRetries(0)
                        .model("claude-stub").maxTokens(256).build())
                .build();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", model);
        gateway = new SpringAiAnthropicGateway(beans.getBeanProvider(ChatModel.class));
    }

    ModelRequest request(List<ModelMessage> messages) {
        return new ModelRequest(UUID.randomUUID(), MODEL, "You analyse stock.", messages, List.of(FORECAST), 256,
                Instant.now().plusSeconds(30), null);
    }

    @Test
    void toolCallIsReturnedCanonicallyAndNeverExecuted() {
        stub.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));

        ModelTurn turn = gateway.complete(request(List.of(new ModelMessage.User("Assess variant-456."))));

        assertThat(turn.finishReason()).isEqualTo(ModelTurn.FinishReason.TOOL_USE);
        assertThat(turn.toolCalls()).containsExactly(new ToolCall("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));
        assertThat(turn.usage()).isEqualTo(new ModelUsage(120, 30));
        assertThat(stub.requests()).hasSize(1);
        assertThat(stub.requests().getFirst()).contains("\"get_inventory_forecast\"").contains("input_schema")
                .contains("You analyse stock.");
    }

    @Test
    void declaredToolsRefuseToRunInsideSpringAi() {
        SpringAiAnthropicGateway.DeclaredTool tool = new SpringAiAnthropicGateway.DeclaredTool(FORECAST);

        assertThat(tool.getToolDefinition().name()).isEqualTo("get_inventory_forecast");
        assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent runtime");
    }

    @Test
    void toolResultGoesBackWithItsIdAndTheFinalTextIsComplete() {
        stub.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));
        stub.enqueue(AnthropicStubServer.text("{}"));
        ModelMessage.User task = new ModelMessage.User("Assess variant-456.");
        ModelTurn first = gateway.complete(request(List.of(task)));

        ModelTurn second = gateway.complete(request(List.of(task, first.asMessage(),
                new ModelMessage.ToolResults(List.of(new ModelMessage.ToolResult("toolu_1", "get_inventory_forecast",
                        "{\"daysOfCover\":\"2.5714\",\"evidence\":\"ev-stock-104\"}", false))))));

        assertThat(second.finishReason()).isEqualTo(ModelTurn.FinishReason.COMPLETE);
        assertThat(second.text()).isEqualTo("{}");
        assertThat(second.toolCalls()).isEmpty();
        assertThat(stub.requests().get(1)).contains("tool_result").contains("toolu_1").contains("ev-stock-104");
    }

    @Test
    void truncatedAnswerIsMarked() {
        stub.enqueue(AnthropicStubServer.truncated("{\\\"disposition\\\":"));

        ModelTurn turn = gateway.complete(request(List.of(new ModelMessage.User("Assess."))));

        assertThat(turn.finishReason()).isEqualTo(ModelTurn.FinishReason.MAX_TOKENS);
    }

    @Test
    void providerErrorsAreTyped() {
        stub.enqueue(429, AnthropicStubServer.error("rate_limit_error"));
        stub.enqueue(529, AnthropicStubServer.error("overloaded_error"));
        stub.enqueue(400, AnthropicStubServer.error("invalid_request_error"));

        assertThatThrownBy(() -> gateway.complete(request(List.of(new ModelMessage.User("a")))))
                .isInstanceOfSatisfying(ModelFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(ModelFailure.Type.RATE_LIMITED);
                    assertThat(failure.retryable()).isTrue();
                });
        assertThatThrownBy(() -> gateway.complete(request(List.of(new ModelMessage.User("b")))))
                .isInstanceOfSatisfying(ModelFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(ModelFailure.Type.UNAVAILABLE));
        assertThatThrownBy(() -> gateway.complete(request(List.of(new ModelMessage.User("c")))))
                .isInstanceOfSatisfying(ModelFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(ModelFailure.Type.REJECTED);
                    assertThat(failure.retryable()).isFalse();
                    // The provider's message text is not carried: only the error class.
                    assertThat(failure.getMessage()).isEqualTo("BadRequestException");
                });
    }

    @Test
    void onlyAnthropicModelsAreAccepted() {
        assertThatThrownBy(() -> gateway.capabilities(new ModelId("other", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
