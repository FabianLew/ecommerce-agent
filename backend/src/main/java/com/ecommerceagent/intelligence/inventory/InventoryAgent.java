package com.ecommerceagent.intelligence.inventory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.Proposer;
import com.ecommerceagent.decisions.ProposerKind;
import com.ecommerceagent.intelligence.agent.AgentDefinition;
import com.ecommerceagent.intelligence.agent.RunLimits;

/** The Inventory Agent profile (document 02 §3). Its tools and limits are code, not configuration. */
public final class InventoryAgent {

    public static final String SKILL = "inventory-guardian@1.0.0";
    public static final AgentDefinition DEFINITION = new AgentDefinition("inventory-agent", "1.0.0",
            "inventory-agent", Set.of(SKILL),
            Set.of(InventoryReadTools.FORECAST, InventoryReadTools.REPLENISHMENT, InventoryReadTools.MAPPINGS,
                    InventoryReadTools.DELIVERY, InventoryReadTools.RECENT_ACTIONS),
            "INVENTORY_BALANCED",
            new RunLimits(5, 8, Duration.ofSeconds(60), 12_000, 4_000, new BigDecimal("0.10"), 1),
            DecisionProposal.SCHEMA_VERSION);
    public static final Proposer PROPOSER = new Proposer(ProposerKind.AGENT, DEFINITION.name(), DEFINITION.version());

    private InventoryAgent() {
    }
}
