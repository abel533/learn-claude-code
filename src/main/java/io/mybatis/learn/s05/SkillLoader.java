package io.mybatis.learn.s05;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 技能加载器 —— 按需注入领域知识，避免系统提示膨胀。
 * <p>
 * 两层技能注入系统:
 * <pre>
 *   Layer 1 (cheap): 技能名称和描述注入系统提示 (~100 tokens/skill)
 *   Layer 2 (on demand): 完整技能内容通过 tool_result 返回
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s05_skill_loading.py} 中的 {@code SkillLoader} 类。
 * 扫描 {@code skills/<name>/SKILL.md}，解析 YAML front matter 提取元数据。
 */
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);

    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    record SkillInfo(Map<String, String> meta, String body, String path) {
    }

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    /**
     * TIP: 对应 Python {@code SkillLoader._load_all()}。
     * 递归扫描 skills 目录下所有 SKILL.md 文件。
     */
    private void loadAll(Path skillsDir) {
        if (!Files.exists(skillsDir)) return;
        try (Stream<Path> paths = Files.walk(skillsDir)) {
            paths.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p);
                            var parsed = parseFrontmatter(text);
                            String name = parsed.meta().getOrDefault("name", p.getParent().getFileName().toString());
                            skills.put(name, new SkillInfo(parsed.meta(), parsed.body(), p.toString()));
                        } catch (IOException e) {
                            System.err.println("Warning: Failed to load skill from " + p + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Warning: Failed to scan skills directory: " + e.getMessage());
        }
    }

    record ParsedSkill(Map<String, String> meta, String body) {
    }

    /**
     * 解析 YAML front matter。
     * TIP: 对应 Python {@code SkillLoader._parse_frontmatter(text)}。
     */
    private ParsedSkill parseFrontmatter(String text) {
        Matcher m = FRONTMATTER_PATTERN.matcher(text);
        if (!m.matches()) {
            return new ParsedSkill(Map.of(), text);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : m.group(1).strip().split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                meta.put(line.substring(0, colonIdx).strip(), line.substring(colonIdx + 1).strip());
            }
        }
        return new ParsedSkill(meta, m.group(2).strip());
    }

    /**
     * Layer 1: 获取所有技能的简短描述（用于系统提示注入）。
     * TIP: 对应 Python {@code SkillLoader.get_descriptions()}。
     */
    public String getDescriptions() {
        if (skills.isEmpty()) return "(no skills available)";
        StringBuilder sb = new StringBuilder();
        for (var entry : skills.entrySet()) {
            String desc = entry.getValue().meta().getOrDefault("description", "No description");
            String tags = entry.getValue().meta().getOrDefault("tags", "");
            sb.append("  - ").append(entry.getKey()).append(": ").append(desc);
            if (!tags.isEmpty()) sb.append(" [").append(tags).append("]");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Layer 2: 加载指定技能的完整内容。
     * TIP: 对应 Python {@code SkillLoader.get_content(name)}，
     * 同时也是 {@code TOOL_HANDLERS["load_skill"]} 的处理函数。
     */
    @Tool(description = "Load specialized knowledge by name. "
            + "Call this before tackling unfamiliar topics to get domain-specific instructions.")
    public String loadSkill(@ToolParam(description = "Skill name to load") String name) {
        SkillInfo skill = skills.get(name);
        if (skill == null) {
            return "Error: Unknown skill '" + name + "'. Available: " + String.join(", ", skills.keySet());
        }
        return "<skill name=\"" + name + "\">\n" + skill.body() + "\n</skill>";
    }
}
