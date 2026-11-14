package com.ecommerceagent.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.ecommerceagent.spike.decisions.RiskEvaluator;
import com.ecommerceagent.spike.workflow.PingWorkflow;
import com.ecommerceagent.spike.workflow.PingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.core.ApplicationModules;

/**
 * B-02: one Spring context with Spring Boot, Spring AI (Anthropic), Spring Modulith and the
 * Temporal starter (in-process test server). Passing means the versions in build.gradle.kts
 * work together at runtime, not only at compile time.
 */
@SpringBootTest(properties = {
        "spring.temporal.test-server.enabled=true",
        "spring.ai.anthropic.base-url=http://127.0.0.1:9"
})
class StackCompatibilityTest {

    @Autowired
    ChatModel chatModel;

    @Autowired
    WorkflowClient workflowClient;

    @Autowired
    RiskEvaluator riskEvaluator;

    @Test
    void springAiAnthropicChatModelIsAutoConfigured() {
        assertThat(chatModel.getClass().getName())
                .isEqualTo("org.springframework.ai.anthropic.AnthropicChatModel");
    }

    @Test
    void temporalWorkflowRunsThroughAutoDiscoveredWorkerAndActivity() {
        PingWorkflow workflow = workflowClient.newWorkflowStub(
                PingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(PingWorkflowImpl.TASK_QUEUE)
                        .setWorkflowId("spike-ping-1")
                        .build());

        assertThat(workflow.ping("store-01")).isEqualTo("pong:store-01");
    }

    @Test
    void springModulithVerifiesModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(SpikeApplication.class);
        modules.verify();

        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .contains("inventory", "decisions", "workflow");
    }

    @Test
    void crossModuleBeanWiringWorks() {
        // Scenario from document 03: 18 units, 49 sold in 7 days -> 2.5714 days of cover (< 3).
        assertThat(riskEvaluator.isLowCoverage(18, 49, 7, new BigDecimal("3"))).isTrue();
        assertThat(riskEvaluator.isLowCoverage(18, 0, 7, new BigDecimal("3"))).isFalse();
    }
}
