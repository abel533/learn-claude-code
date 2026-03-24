# s05: Skills (技能加载)

`s01 > s02 > s03 > s04 > [ s05 ] s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"用到什么知识, 临时加载什么知识"* -- 通过 tool_result 注入, 不塞 system prompt。
>
> **Harness 层**: 按需知识 -- 模型开口要时才给的领域专长。

## 问题

你希望智能体遵循特定领域的工作流: git 约定、测试模式、代码审查清单。全塞进系统提示太浪费 -- 10 个技能, 每个 2000 token, 就是 20,000 token, 大部分跟当前任务毫无关系。

## 解决方案

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

第一层: 系统提示中放技能名称 (低成本)。第二层: tool_result 中按需放完整内容。

## 工作原理

1. 每个技能是一个目录, 包含 `SKILL.md` 文件和 YAML frontmatter。

```
skills/
  pdf/
    SKILL.md       # ---\n name: pdf\n description: Process PDF files\n ---\n ...
  code-review/
    SKILL.md       # ---\n name: code-review\n description: Review code\n ---\n ...
```

2. SkillLoader 递归扫描 `SKILL.md` 文件, 用目录名作为技能标识。

```java
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);

    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    record SkillInfo(Map<String, String> meta, String body, String path) {}

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    /** 递归扫描 skills 目录下所有 SKILL.md 文件 */
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

    /** Layer 1: 获取所有技能的简短描述（用于系统提示注入） */
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

    /** Layer 2: 加载指定技能的完整内容（作为 @Tool 方法） */
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

3. 第一层写入系统提示。第二层通过 SkillLoader 上的 `@Tool` 注解方法按需加载。

```java
public S05SkillLoading(ChatModel chatModel) {
    Path skillsDir = Path.of(System.getProperty("user.dir"), "skills");
    SkillLoader skillLoader = new SkillLoader(skillsDir);

    // Layer 1: 技能元数据注入系统提示
    String system = "You are a coding agent at " + System.getProperty("user.dir") + ".\n"
            + "Use loadSkill to access specialized knowledge.\n\n"
            + "Skills available:\n"
            + skillLoader.getDescriptions();

    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(
                    new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(),
                    skillLoader  // Layer 2: loadSkill @Tool 方法
            )
            .build();
}
```

模型知道有哪些技能 (便宜), 需要时再加载完整内容 (贵)。

## 相对 s04 的变更

| 组件           | 之前 (s04)       | 之后 (s05)                     |
|----------------|------------------|--------------------------------|
| Tools          | 5 (基础 + task)  | 5 (基础 + load_skill)          |
| 系统提示       | 静态字符串       | + 技能描述列表                 |
| 知识库         | 无               | skills/\*/SKILL.md 文件        |
| 注入方式       | 无               | 两层 (系统提示 + result)       |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s05.S05SkillLoading
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `What skills are available?`
2. `Load the agent-builder skill and follow its instructions`
3. `I need to do a code review -- load the relevant skill first`
4. `Build an MCP server using the mcp-builder skill`
