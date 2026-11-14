package com.ecommerceagent.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * B-03: the runtime, not Spring AI, must own the tool loop (document 02, sections 3-4).
 *
 * <p>Spring AI 2.0 no longer executes tools inside {@code ChatModel.call()}; the loop lives in
 * {@code ToolCallingAdvisor}, which {@code ChatClient} registers by default. The runtime therefore
 * talks to {@link ChatModel} directly and keeps ChatClient tool calling disabled.
 *
 * <p>Uses the auto-configured Anthropic ChatModel against a local stub of the Messages API,
 * so no API key or network access is required.
 */
@SpringBootTest(properties = {
        "spring.temporal.test-server.enabled=true",
        "spring.ai.anthropic.max-retries=0"
})
class ToolCallingBoundaryTest {

    static final AnthropicStubServer STUB;

    static {
        try {
            STUB = new AnthropicStubServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void anthropicStub(DynamicPropertyRegistry registry) {
        // Explicit base URL: never rely on ANTHROPIC_BASE_URL from the environment.
        registry.add("spring.ai.anthropic.base-url", STUB::baseUrl);
        registry.add("spring.ai.anthropic.api-key", () -> "test-key");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    ChatModel chatModel;

    @Autowired
    ChatClient.Builder chatClientBuilder;

    /** Counts executions that happen inside Spring AI (via the registered ToolCallback). */
    final AtomicInteger springAiSideExecutions = new AtomicInteger();

    record ForecastRequest(String productVariantId) {
    }

    ToolCallback forecastTool() {
        return FunctionToolCallback
                .builder("get_inventory_forecast", (ForecastRequest request) -> {
                    springAiSideExecutions.incrementAndGet();
                    return Map.of("productVariantId", request.productVariantId(), "daysOfCover", "2.5714");
                })
                .description("Returns sellable stock, sales velocity and days of cover for a product variant.")
                .inputType(ForecastRequest.class)
                .build();
    }

    AnthropicChatOptions.Builder optionsBuilder() {
        return AnthropicChatOptions.builder()
                .model("claude-spike")
                .maxTokens(1024)
                .toolCallbacks(List.of(forecastTool()));
    }

    AnthropicChatOptions options() {
        return (AnthropicChatOptions) optionsBuilder().build();
    }

    @BeforeEach
    void resetStub() {
        STUB.reset();
        springAiSideExecutions.set(0);
    }

    @Test
    void toolCallIsReturnedToCallerAndNotExecutedBySpringAi() {
        STUB.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));

        ChatResponse response = chatModel.call(new Prompt(
                new UserMessage("Assess inventory risk for variant-456."), options()));

        assertThat(response.hasToolCalls()).isTrue();
        AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().getFirst();
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.name()).isEqualTo("get_inventory_forecast");
        assertThat(call.arguments()).contains("variant-456");

        assertThat(springAiSideExecutions).hasValue(0);
        assertThat(STUB.requests()).hasSize(1);
        // The tool definition was sent to the model, so the model could choose it.
        assertThat(STUB.requests().getFirst()).contains("\"get_inventory_forecast\"").contains("input_schema");
    }

    @Test
    void runtimeDrivesTheLoopAndSendsToolResultBack() {
        STUB.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));
        STUB.enqueue(AnthropicStubServer.text("Coverage is about 2.6 days."));
        ReadOnlyToolDispatcher dispatcher = new ReadOnlyToolDispatcher(Map.of(
                "get_inventory_forecast", args -> "{\"daysOfCover\":\"2.5714\",\"evidenceIds\":[\"ev-stock-104\"]}"));

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Assess inventory risk for variant-456."));

        ChatResponse first = chatModel.call(new Prompt(history, options()));
        AssistantMessage assistant = first.getResult().getOutput();
        history.add(assistant);

        List<ToolResponseMessage.ToolResponse> results = assistant.getToolCalls().stream()
                .map(dispatcher::dispatch)
                .toList();
        history.add(ToolResponseMessage.builder().responses(results).build());

        ChatResponse second = chatModel.call(new Prompt(history, options()));

        assertThat(second.hasToolCalls()).isFalse();
        assertThat(second.getResult().getOutput().getText()).isEqualTo("Coverage is about 2.6 days.");
        assertThat(dispatcher.executions()).isEqualTo(1);
        assertThat(springAiSideExecutions).hasValue(0);
        assertThat(STUB.requests()).hasSize(2);
        assertThat(STUB.requests().get(1))
                .contains("tool_result")
                .contains("toolu_1")
                .contains("ev-stock-104");
    }

    @Test
    void toolOutsideAllowlistIsNeverExecuted() {
        // IG-11: the model asks for a mutating tool that was never offered to it.
        STUB.enqueue(AnthropicStubServer.toolUse("toolu_9", "pause_ad", "{\"adId\":\"ad-9001\"}"));
        ReadOnlyToolDispatcher dispatcher = new ReadOnlyToolDispatcher(Map.of(
                "get_inventory_forecast", args -> "{}"));

        ChatResponse response = chatModel.call(new Prompt(
                new UserMessage("Assess inventory risk for variant-456."), options()));

        AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().getFirst();
        ToolResponseMessage.ToolResponse result = dispatcher.dispatch(call);

        assertThat(call.name()).isEqualTo("pause_ad");
        assertThat(result.responseData()).contains("TOOL_NOT_ALLOWED");
        assertThat(dispatcher.executions()).isZero();
        assertThat(springAiSideExecutions).hasValue(0);
    }

    @Test
    void autoConfiguredChatClientDoesNotExecuteTools() {
        // Defence in depth: application.yml sets spring.ai.chat.client.tool-calling.enabled=false,
        // so even a ChatClient taken from the context returns tool calls instead of running them.
        STUB.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));

        ChatResponse response = chatClientBuilder.build().prompt()
                .user("Assess inventory risk for variant-456.")
                .options(optionsBuilder())
                .call()
                .chatResponse();

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(springAiSideExecutions).hasValue(0);
        assertThat(STUB.requests()).hasSize(1);
    }

    @Test
    void chatClientWithToolCallingAdvisorWouldRunToolsInsideSpringAi() {
        // Documents the hazard ADR-0002 guards against: Spring AI's default ChatClient behaviour
        // registers ToolCallingAdvisor, which runs the tool and makes a second model request on its own.
        STUB.enqueue(AnthropicStubServer.toolUse("toolu_1", "get_inventory_forecast",
                "{\"productVariantId\":\"variant-456\"}"));
        STUB.enqueue(AnthropicStubServer.text("done"));

        ChatResponse response = ChatClient.builder(chatModel).build().prompt()
                .user("Assess inventory risk for variant-456.")
                .options(optionsBuilder())
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(true))
                .call()
                .chatResponse();

        assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
        assertThat(springAiSideExecutions).hasValue(1);
        assertThat(STUB.requests()).hasSize(2);
    }

    /**
     * Stand-in for the runtime's ToolDispatcher: executes only allowlisted read tools and
     * reports a structured error for anything else. The real one validates schemas and scope too.
     */
    static final class ReadOnlyToolDispatcher {

        private final Map<String, Function<String, String>> allowlist;
        private int executions;

        ReadOnlyToolDispatcher(Map<String, Function<String, String>> allowlist) {
            this.allowlist = allowlist;
        }

        ToolResponseMessage.ToolResponse dispatch(AssistantMessage.ToolCall call) {
            Function<String, String> tool = allowlist.get(call.name());
            if (tool == null) {
                return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        "{\"error\":\"TOOL_NOT_ALLOWED\"}");
            }
            executions++;
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(), tool.apply(call.arguments()));
        }

        int executions() {
            return executions;
        }
    }
}
