package com.ecommerceagent.intelligence.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One approved skill version: its instructions, manifest facts, files and the hash a run pins. */
public record SkillPackage(String name, String version, String hash, String instructions, String outputSchemaVersion,
        Set<String> requiredReadTools, Map<String, String> files, List<String> evaluationCases) {

    public SkillPackage {
        requiredReadTools = Set.copyOf(requiredReadTools);
        files = Map.copyOf(files);
        evaluationCases = List.copyOf(evaluationCases);
    }

    public String reference() {
        return name + "@" + version;
    }
}
