# s05: Skills

`s01 > s02 > s03 > s04 > [ s05 ] s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"Load knowledge when you need it, not upfront"* -- inject via tool_result, not the system prompt.
>
> **Harness layer**: On-demand knowledge -- domain expertise, loaded when the model asks.

## Problem

You want the agent to follow domain-specific workflows: git conventions, testing patterns, code review checklists. Putting everything in the system prompt wastes tokens -- 10 skills at 2000 tokens each = 20,000 tokens, most of which are irrelevant to any given task.

## Solution

```
System prompt (Layer 1 -- always present):
+--------------------------------------+
| You are a coding agent.              |
| Skills available:                    |
|   - git: Git workflow helpers        |  ~100 tokens/skill
|   - test: Testing best practices     |
+--------------------------------------+

When model calls load_skill("git"):
+--------------------------------------+
| tool_result (Layer 2 -- on demand):  |
| <skill name="git">                   |
|   Full git workflow instructions...  |  ~2000 tokens
|   Step 1: ...                        |
| </skill>                             |
+--------------------------------------+
```

Layer 1: skill *names* in system prompt (cheap). Layer 2: full *body* via tool_result (on demand).

## How It Works

1. Each skill is a directory containing a `SKILL.md` file with YAML frontmatter.

```
skills/
  pdf/
    SKILL.md       # ---\n name: pdf\n description: Process PDF files\n ---\n ...
  code-review/
    SKILL.md       # ---\n name: code-review\n description: Review code\n ---\n ...
```

2. SkillLoader recursively scans for `SKILL.md` files, using the directory name as the skill identifier.

```java
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);

    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    record SkillInfo(Map<String, String> meta, String body, String path) {}

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    /** Recursively scan all SKILL.md files under the skills directory */
    private void loadAll(Path skillsDir) {
        if (!Files.exists(skillsDir)) return;
        try (Stream<Path> paths = Files.walk(skillsDir)) {
            paths.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(p -> {
                        String text = Files.readString(p);
                        var parsed = parseFrontmatter(text);
                        String name = parsed.meta().getOrDefault("name",
                                p.getParent().getFileName().toString());
                        skills.put(name, new SkillInfo(
                                parsed.meta(), parsed.body(), p.toString()));
                    });
        }
    }

    /** Layer 1: Get short descriptions of all skills (for system prompt injection) */
    public String getDescriptions() {
        if (skills.isEmpty()) return "(no skills available)";
        StringBuilder sb = new StringBuilder();
        for (var entry : skills.entrySet()) {
            String desc = entry.getValue().meta()
                    .getOrDefault("description", "No description");
            sb.append("  - ").append(entry.getKey())
                    .append(": ").append(desc).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Layer 2: Load full content of a specified skill (as @Tool method) */
    @Tool(description = "Load specialized knowledge by name.")
    public String loadSkill(
            @ToolParam(description = "Skill name to load") String name) {
        SkillInfo skill = skills.get(name);
        if (skill == null)
            return "Error: Unknown skill '" + name + "'. Available: "
                    + String.join(", ", skills.keySet());
        return "<skill name=\"" + name + "\">\n"
                + skill.body() + "\n</skill>";
    }
}
```

3. Layer 1 goes into the system prompt. Layer 2 is loaded on demand via the `@Tool` annotated method on SkillLoader.

```java
public S05SkillLoading(ChatModel chatModel) {
    Path skillsDir = Path.of(System.getProperty("user.dir"), "skills");
    SkillLoader skillLoader = new SkillLoader(skillsDir);

    // Layer 1: Skill metadata injected into system prompt
    String system = "You are a coding agent at " + System.getProperty("user.dir") + ".\n"
            + "Use loadSkill to access specialized knowledge.\n\n"
            + "Skills available:\n"
            + skillLoader.getDescriptions();

    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(
                    new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(),
                    skillLoader  // Layer 2: loadSkill @Tool method
            )
            .build();
}
```

The model learns what skills exist (cheap) and loads them when relevant (expensive).

## What Changed From s04

| Component      | Before (s04)     | After (s05)                    |
|----------------|------------------|--------------------------------|
| Tools          | 5 (base + task)  | 5 (base + load_skill)          |
| System prompt  | Static string    | + skill descriptions           |
| Knowledge      | None             | skills/\*/SKILL.md files       |
| Injection      | None             | Two-layer (system + result)    |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s05.S05SkillLoading
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `What skills are available?`
2. `Load the agent-builder skill and follow its instructions`
3. `I need to do a code review -- load the relevant skill first`
4. `Build an MCP server using the mcp-builder skill`
