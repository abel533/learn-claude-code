# s05: Skills (スキルローディング)

`s01 > s02 > s03 > s04 > [ s05 ] s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"必要な知識を、必要な時に読み込む"* -- system prompt ではなく tool_result で注入。
>
> **Harness 層**: オンデマンド知識 -- モデルが求めた時だけ渡すドメイン専門性。

## 問題

エージェントにドメイン固有のワークフローを遵守させたい: git の規約、テストパターン、コードレビューチェックリスト。すべてをシステムプロンプトに入れるとトークンの浪費だ -- 10スキル x 2000トークン = 20,000トークン、大半が当面のタスクとは無関係。

## 解決策

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

第1層: スキル名をシステムプロンプトに（低コスト）。第2層: 完全なコンテンツを tool_result でオンデマンド配信。

## 仕組み

1. 各スキルは `SKILL.md` ファイルを含むディレクトリで、YAML frontmatter 付き。

```
skills/
  pdf/
    SKILL.md       # ---\n name: pdf\n description: Process PDF files\n ---\n ...
  code-review/
    SKILL.md       # ---\n name: code-review\n description: Review code\n ---\n ...
```

2. SkillLoader が `SKILL.md` を再帰的にスキャンし、ディレクトリ名をスキル識別子として使用する。

```java
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);

    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    record SkillInfo(Map<String, String> meta, String body, String path) {}

    public SkillLoader(Path skillsDir) {
        loadAll(skillsDir);
    }

    /** skills ディレクトリ配下のすべての SKILL.md ファイルを再帰スキャン */
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

    /** Layer 1: 全スキルの短い説明を取得（システムプロンプト注入用） */
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

    /** Layer 2: 指定スキルの完全なコンテンツを読み込む（@Tool メソッドとして） */
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

3. 第1層はシステムプロンプトに配置。第2層は SkillLoader 上の `@Tool` アノテーションメソッドでオンデマンド読み込み。

```java
public S05SkillLoading(ChatModel chatModel) {
    Path skillsDir = Path.of(System.getProperty("user.dir"), "skills");
    SkillLoader skillLoader = new SkillLoader(skillsDir);

    // Layer 1: スキルメタデータをシステムプロンプトに注入
    String system = "You are a coding agent at " + System.getProperty("user.dir") + ".\n"
            + "Use loadSkill to access specialized knowledge.\n\n"
            + "Skills available:\n"
            + skillLoader.getDescriptions();

    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(
                    new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(),
                    skillLoader  // Layer 2: loadSkill @Tool メソッド
            )
            .build();
}
```

モデルはどのスキルが存在するかを知り（低コスト）、必要な時にだけ完全なコンテンツを読み込む（高コスト）。

## s04 からの変更点

| コンポーネント   | 変更前 (s04)     | 変更後 (s05)                   |
|----------------|------------------|--------------------------------|
| Tools          | 5 (基本 + task)  | 5 (基本 + load_skill)          |
| システムプロンプト | 静的文字列     | + スキル説明リスト              |
| 知識ベース      | なし             | skills/\*/SKILL.md ファイル     |
| 注入方式       | なし             | 二層構造 (システムプロンプト + result) |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s05.S05SkillLoading
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `What skills are available?`
2. `Load the agent-builder skill and follow its instructions`
3. `I need to do a code review -- load the relevant skill first`
4. `Build an MCP server using the mcp-builder skill`
