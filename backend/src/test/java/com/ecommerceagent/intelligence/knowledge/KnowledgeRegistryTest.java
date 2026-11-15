package com.ecommerceagent.intelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** B-29: the approved packages load from the application, and any change of any file is a new hash. */
class KnowledgeRegistryTest {

    final KnowledgeRegistry registry = new KnowledgeRegistry();

    @Test
    void inventoryGuardianPackageLoadsWithItsContract() {
        SkillPackage skill = registry.skill("inventory-guardian@1.0.0").orElseThrow();

        assertThat(skill.requiredReadTools()).containsExactlyInAnyOrder("get_inventory_forecast",
                "get_replenishment_plan", "get_product_ad_mappings", "get_ad_delivery_state", "get_recent_actions");
        assertThat(skill.outputSchemaVersion()).isEqualTo("1");
        assertThat(skill.instructions()).startsWith("# Inventory Guardian").doesNotContain("metadata:");
        assertThat(skill.files()).containsKeys("SKILL.md", "manifest.yaml", "references/ad-mapping-rules.md",
                "examples/pause-single-product.json", "evals/cases.jsonl");
        assertThat(skill.evaluationCases()).hasSize(8);
        assertThat(skill.hash()).isEqualTo(KnowledgeRegistry.hash(skill.files())).hasSize(64);
        assertThat(registry.skill("inventory-guardian@2.0.0")).isEmpty();
    }

    @Test
    void hashCoversEveryPathAndByte() {
        SkillPackage skill = registry.skill("inventory-guardian@1.0.0").orElseThrow();
        Map<String, String> edited = new HashMap<>(skill.files());
        edited.put("references/ad-mapping-rules.md", edited.get("references/ad-mapping-rules.md") + " ");
        Map<String, String> renamed = new HashMap<>(skill.files());
        renamed.put("references/renamed.md", renamed.remove("references/ad-mapping-rules.md"));

        assertThat(KnowledgeRegistry.hash(edited)).isNotEqualTo(skill.hash());
        assertThat(KnowledgeRegistry.hash(renamed)).isNotEqualTo(skill.hash());
    }

    @Test
    void promptTemplatesFillEveryPlaceholderOrFail() {
        PromptPackage prompts = registry.prompts("inventory-agent").orElseThrow();

        assertThat(prompts.system()).contains("{{skill}}");
        assertThat(PromptPackage.fill("a {{x}} b", Map.of("x", "1"))).isEqualTo("a 1 b");
        assertThatThrownBy(() -> PromptPackage.fill(prompts.task(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
