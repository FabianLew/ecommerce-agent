package com.ecommerceagent.intelligence.inventory;

import java.util.Set;

import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.Proposer;
import com.ecommerceagent.decisions.ProposerKind;
import com.ecommerceagent.intelligence.agent.AgentDefinition;

/** The Inventory Agent profile (document 02 §3). Its tools are code, not configuration; its limits are
 * the versioned run policy {@link InventoryAgentRunPolicy}. */
public final class InventoryAgent {

    public static final String SKILL = "inventory-guardian@1.0.0";
    public static final AgentDefinition DEFINITION = new AgentDefinition("inventory-agent", "1.0.0",
            "inventory-agent", Set.of(SKILL),
            Set.of(InventoryReadTools.FORECAST, InventoryReadTools.REPLENISHMENT, InventoryReadTools.MAPPINGS,
                    InventoryReadTools.DELIVERY, InventoryReadTools.RECENT_ACTIONS),
            "INVENTORY_BALANCED",
            DecisionProposal.SCHEMA_VERSION);
    public static final Proposer PROPOSER = new Proposer(ProposerKind.AGENT, DEFINITION.name(), DEFINITION.version());

    private InventoryAgent() {
    }
}
