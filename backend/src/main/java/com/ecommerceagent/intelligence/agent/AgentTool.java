package com.ecommerceagent.intelligence.agent;

import tools.jackson.databind.JsonNode;

/** A backend capability the model may ask for (document 02 §5). Arguments were validated against the schema. */
public interface AgentTool {

    ToolDefinition definition();

    ToolOutcome execute(ToolExecutionContext context, JsonNode arguments);
}
