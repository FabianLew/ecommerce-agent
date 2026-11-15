package com.ecommerceagent.intelligence.model.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ecommerceagent.intelligence.model.ModelCapabilities;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.ModelGateway;
import com.ecommerceagent.intelligence.model.ModelId;
import com.ecommerceagent.intelligence.model.ModelMessage;
import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ModelTurn;
import com.ecommerceagent.intelligence.model.ModelUsage;
import com.ecommerceagent.intelligence.model.ToolCall;
import com.ecommerceagent.intelligence.model.ToolSpec;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ModelGateway over Spring AI's Anthropic {@link ChatModel} (ADR-0002, ADR-0016 point 2). It calls the ChatModel
 * directly, declares tools with callbacks that refuse to run, and maps the response to a canonical turn. Retries are
 * the runtime's, so Spring AI's are off in the configuration.
 */
@Component
class SpringAiAnthropicGateway implements ModelGateway {

    static final String PROVIDER = "anthropic";

    private final ObjectProvider<ChatModel> chatModel;

    SpringAiAnthropicGateway(ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ModelCapabilities capabilities(ModelId model) {
        requireProvider(model);
        return new ModelCapabilities(true, true, 1_000_000, 128_000);
    }

    @Override
    public boolean available(ModelId model) {
        requireProvider(model);
        return chatModel.getIfAvailable() != null;
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        requireProvider(request.model());
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            // Nothing was sent, so nothing can be charged.
            throw new ModelFailure(ModelFailure.Type.UNAVAILABLE, "No Anthropic chat model is configured", null, null,
                    true, null);
        }
        Duration timeout = Duration.between(Instant.now(), request.deadline());
        if (timeout.isNegative() || timeout.isZero()) {
            throw new ModelFailure(ModelFailure.Type.TIMEOUT, "Deadline passed before the call", null, null, true,
                    null);
        }
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
        options.model(request.model().name());
        options.maxTokens(request.maxOutputTokens());
        options.timeout(timeout);
        options.maxRetries(0);
        options.toolCallbacks(request.tools().stream().map(DeclaredTool::new).map(ToolCallback.class::cast).toList());
        if (request.effort() != null) {
            options.effort(OutputConfig.Effort.of(request.effort().toLowerCase(Locale.ROOT)));
        }
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages(request), options.build()));
        } catch (RuntimeException e) {
            throw failure(e);
        }
        return turn(response);
    }

    private static void requireProvider(ModelId model) {
        if (!PROVIDER.equals(model.provider())) {
            throw new IllegalArgumentException("Not an Anthropic model: " + model);
        }
    }

    private static List<Message> messages(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.system()));
        for (ModelMessage message : request.messages()) {
            switch (message) {
                case ModelMessage.User user -> messages.add(new UserMessage(user.text()));
                case ModelMessage.Assistant assistant -> {
                    if (assistant.providerState() instanceof AssistantMessage original) {
                        messages.add(original);
                    } else {
                        messages.add(AssistantMessage.builder().content(assistant.text())
                                .toolCalls(assistant.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                                        call.id(), "function", call.name(), call.argumentsJson())).toList())
                                .build());
                    }
                }
                case ModelMessage.ToolResults results -> messages.add(ToolResponseMessage.builder()
                        .responses(results.results().stream().map(result -> new ToolResponseMessage.ToolResponse(
                                result.toolCallId(), result.toolName(), result.content())).toList())
                        .build());
            }
        }
        return messages;
    }

    private static ModelTurn turn(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        String requestId = metadata == null ? null : metadata.getId();
        Usage usage = metadata == null ? null : metadata.getUsage();
        ModelUsage modelUsage = usage == null ? ModelUsage.UNKNOWN
                : new ModelUsage(usage.getPromptTokens(), usage.getCompletionTokens());
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        AssistantMessage last = null;
        String finish = null;
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            last = output;
            if (output.getText() != null) {
                text.append(output.getText());
            }
            output.getToolCalls().forEach(call -> calls.add(new ToolCall(call.id(), call.name(), call.arguments())));
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                finish = generation.getMetadata().getFinishReason();
            }
        }
        return new ModelTurn(text.toString(), calls, finishReason(finish, !calls.isEmpty()), modelUsage, requestId,
                last);
    }

    static ModelTurn.FinishReason finishReason(String reason, boolean hasToolCalls) {
        String value = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "end_turn", "stop_sequence", "stop" -> hasToolCalls ? ModelTurn.FinishReason.TOOL_USE
                    : ModelTurn.FinishReason.COMPLETE;
            case "tool_use" -> ModelTurn.FinishReason.TOOL_USE;
            case "max_tokens", "length" -> ModelTurn.FinishReason.MAX_TOKENS;
            case "refusal" -> ModelTurn.FinishReason.REFUSAL;
            default -> hasToolCalls ? ModelTurn.FinishReason.TOOL_USE : ModelTurn.FinishReason.OTHER;
        };
    }

    static ModelFailure failure(RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AnthropicServiceException service) {
                return serviceFailure(service);
            }
            ModelFailure.Type type = switch (cause) {
                case AnthropicIoException ignored -> ModelFailure.Type.TIMEOUT;
                case java.net.http.HttpTimeoutException ignored -> ModelFailure.Type.TIMEOUT;
                case java.io.InterruptedIOException ignored -> ModelFailure.Type.TIMEOUT;
                default -> null;
            };
            if (type != null) {
                // No response: the request may have reached the provider and been processed.
                return new ModelFailure(type, cause.getClass().getSimpleName(), null, null,
                        AnthropicChargeRules.confirmedNotCharged(AnthropicChargeRules.Stage.NO_RESPONSE, 0, null),
                        null);
            }
        }
        return new ModelFailure(ModelFailure.Type.UNKNOWN, error.getClass().getSimpleName(), null, null, null);
    }

    private static ModelFailure serviceFailure(AnthropicServiceException service) {
        String requestId = service.headers().values("request-id").stream().findFirst().orElse(null);
        int status = service.statusCode();
        String errorType = errorType(service);
        // An error event inside a stream arrives as an SseException: the response had started, so output may have
        // been generated and billed.
        AnthropicChargeRules.Stage stage = service instanceof SseException ? AnthropicChargeRules.Stage.STREAM_STARTED
                : AnthropicChargeRules.Stage.ERROR_RESPONSE;
        boolean notCharged = AnthropicChargeRules.confirmedNotCharged(stage, status, errorType);
        ModelFailure.Type type;
        if (status == 408 || status == 504 || "timeout_error".equals(errorType)) {
            // A timeout reported as a response, by the API or a gateway in between.
            type = ModelFailure.Type.TIMEOUT;
        } else if (status == 429 || "rate_limit_error".equals(errorType)) {
            type = ModelFailure.Type.RATE_LIMITED;
        } else if (status >= 500 || "overloaded_error".equals(errorType) || "api_error".equals(errorType)
                || service instanceof SseException) {
            type = ModelFailure.Type.UNAVAILABLE;
        } else {
            type = ModelFailure.Type.REJECTED;
        }
        return new ModelFailure(type, service.getClass().getSimpleName(), null, requestId, notCharged, null);
    }

    private static String errorType(AnthropicServiceException service) {
        try {
            return service.errorType().map(ErrorType::asString).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Sends the definition to the model; refuses to run, because tools run only in the runtime (ADR-0002). */
    record DeclaredTool(ToolSpec spec) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(spec.name()).description(spec.description())
                    .inputSchema(spec.inputSchema()).build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("Tool " + spec.name() + " must run in the agent runtime, not in Spring AI");
        }
    }
}
