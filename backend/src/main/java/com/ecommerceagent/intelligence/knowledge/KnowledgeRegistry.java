package com.ecommerceagent.intelligence.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the approved packages under {@code knowledge/skills} and {@code knowledge/prompts} from the classpath once.
 * A package's hash is SHA-256 over its sorted relative paths and contents, so any change of any file is a new hash.
 */
@Component
public class KnowledgeRegistry {

    static final String SKILLS = "knowledge/skills/";
    static final String PROMPTS = "knowledge/prompts/";

    private final Map<String, SkillPackage> skills = new HashMap<>();
    private final Map<String, PromptPackage> prompts = new HashMap<>();

    public KnowledgeRegistry() {
        this(new PathMatchingResourcePatternResolver());
    }

    KnowledgeRegistry(PathMatchingResourcePatternResolver resolver) {
        Map<String, Map<String, String>> skillFiles = load(resolver, SKILLS);
        skillFiles.forEach((name, files) -> {
            SkillPackage skill = skill(name, files);
            skills.put(skill.reference(), skill);
        });
        load(resolver, PROMPTS).forEach((agent, files) -> {
            String system = files.get("system.md");
            String task = files.get("task.md");
            if (system == null || task == null) {
                throw new IllegalStateException("Prompt package " + agent + " needs system.md and task.md");
            }
            prompts.put(agent, new PromptPackage(agent, hash(files), system, task));
        });
    }

    public Optional<SkillPackage> skill(String reference) {
        return Optional.ofNullable(skills.get(reference));
    }

    public Optional<PromptPackage> prompts(String agent) {
        return Optional.ofNullable(prompts.get(agent));
    }

    private static SkillPackage skill(String name, Map<String, String> files) {
        String manifestText = files.get("manifest.yaml");
        String instructions = files.get("SKILL.md");
        if (manifestText == null || instructions == null) {
            throw new IllegalStateException("Skill " + name + " needs SKILL.md and manifest.yaml");
        }
        JsonNode manifest = YAMLMapper.builder().build().readTree(manifestText);
        if (!name.equals(manifest.path("name").asString())) {
            throw new IllegalStateException("Skill directory " + name + " has manifest name "
                    + manifest.path("name").asString());
        }
        Set<String> tools = new LinkedHashSet<>();
        manifest.path("requiredReadTools").forEach(tool -> tools.add(tool.asString()));
        List<String> cases = new ArrayList<>();
        String evaluation = manifest.path("evaluation").asString("");
        String evalText = files.get(evaluation);
        if (evalText != null) {
            evalText.lines().filter(line -> !line.isBlank()).forEach(cases::add);
        }
        return new SkillPackage(name, manifest.path("version").asString(), hash(files), body(instructions),
                manifest.path("outputSchemaVersion").asString(), tools, files, cases);
    }

    /** SKILL.md without its front matter. */
    private static String body(String skill) {
        if (skill.startsWith("---")) {
            int end = skill.indexOf("\n---", 3);
            if (end > 0) {
                return skill.substring(end + 4).strip();
            }
        }
        return skill.strip();
    }

    /** Files of every package directory under {@code root}, by package name, then relative path. */
    private static Map<String, Map<String, String>> load(PathMatchingResourcePatternResolver resolver, String root) {
        Map<String, Map<String, String>> packages = new TreeMap<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + root + "**/*")) {
                if (!resource.isReadable()) {
                    continue;
                }
                String path = relative(resource.getURI(), root);
                if (path == null || path.endsWith("/")) {
                    continue;
                }
                int slash = path.indexOf('/');
                if (slash < 0) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    packages.computeIfAbsent(path.substring(0, slash), key -> new TreeMap<>())
                            .put(path.substring(slash + 1), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return packages;
    }

    private static String relative(URI uri, String root) {
        String text = uri.toString();
        int at = text.lastIndexOf(root);
        return at < 0 ? null : text.substring(at + root.length());
    }

    static String hash(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(files).forEach((path, content) -> {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(content.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
