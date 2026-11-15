package com.ecommerceagent.intelligence.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.stereotype.Component;

/**
 * All registered tools. A run gets the intersection of the agent's allowed tools, the skill's required tools and
 * the READ effect (ADR-0016 point 3); nothing else is visible to the model or callable through the dispatcher.
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new TreeMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool tool : tools) {
            if (this.tools.put(tool.definition().name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool " + tool.definition().name());
            }
        }
    }

    /** The tools one run may use, in name order. */
    public ToolSet forRun(AgentDefinition agent, Set<String> skillTools) {
        Map<String, AgentTool> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, AgentTool> entry : tools.entrySet()) {
            String name = entry.getKey();
            if (agent.allowedReadTools().contains(name) && skillTools.contains(name)
                    && entry.getValue().definition().effect() == ToolEffect.READ) {
                allowed.put(name, entry.getValue());
            }
        }
        return new ToolSet(allowed);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** The tools of a run, with the hash of their definitions that the run pins. */
    public record ToolSet(Map<String, AgentTool> tools) {

        public ToolSet {
            tools = Map.copyOf(tools);
        }

        public AgentTool get(String name) {
            return tools.get(name);
        }

        public List<ToolDefinition> definitions() {
            return tools.values().stream().map(AgentTool::definition)
                    .sorted(java.util.Comparator.comparing(ToolDefinition::name)).toList();
        }

        public String hash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (ToolDefinition definition : definitions()) {
                    digest.update((definition.name() + "@" + definition.version() + ":" + definition.effect() + ":"
                            + definition.description() + ":" + CanonicalJson.write(definition.inputSchema()) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
