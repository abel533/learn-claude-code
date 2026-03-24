# s03: TodoWrite (Todo書き込み)

`s01 > s02 > [ s03 ] s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"計画のないエージェントは行き当たりばったり"* -- まずステップを書き出し、それから実行。完了率は倍増する。
>
> **Harness 層**: 計画 -- 航路を描かずにモデルを軌道に乗せる。

## 問題

マルチステップのタスクで、モデルは進捗を見失う -- 既にやったことを繰り返したり、ステップを飛ばしたり、脱線したりする。会話が長くなるほど悪化する: ツール結果がコンテキストを埋め尽くし、システムプロンプトの影響力が徐々に薄れる。10ステップのリファクタリングでステップ1-3を完了した後、即興を始めてしまう。ステップ4-10はもう注意の外だ。

## 解決策

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> | Tools   |
| prompt |      |       |      | + todo  |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                          |
              +-----------+-----------+
              | TodoManager state     |
              | [ ] task A            |
              | [>] task B  <- doing  |
              | [x] task C            |
              +-----------------------+
                          |
              毎回のリクエスト時に defaultSystem() で
                最新の todo 状態をシステムプロンプトに注入
```

## 仕組み

1. TodoManager はステータス付きアイテムを保持する。同時に `in_progress` にできるのは1つだけ。

```java
public class TodoManager {

    public record TodoItem(String id, String text, String status) {}

    private List<TodoItem> items = new ArrayList<>();

    @Tool(description = "Update the full task list to track progress. "
            + "Each item must have id, text, status (pending/in_progress/completed). "
            + "Only one task can be in_progress at a time. Max 20 items.")
    public String updateTodos(
            @ToolParam(description = "The complete list of todo items")
            List<TodoItem> items) {
        if (items.size() > 20) return "Error: Max 20 todos allowed";
        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;
        for (TodoItem item : items) {
            String status = (item.status() != null)
                    ? item.status().toLowerCase() : "pending";
            if ("in_progress".equals(status)) inProgressCount++;
            validated.add(new TodoItem(item.id(), item.text().trim(), status));
        }
        if (inProgressCount > 1)
            return "Error: Only one task can be in_progress at a time";
        this.items = validated;
        return render();
    }
}
```

2. `TodoManager` は `defaultTools()` で登録し、`@Tool` アノテーションメソッドが自動的にツールとして公開される。

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem(system)
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                todoManager          // @Tool アノテーションメソッドが自動登録
        )
        .build();
```

3. システムプロンプト注入: ユーザー入力のたびに、最新の todo 状態をシステムプロンプトに注入し、更新指示を強調する。

```java
// 動的システムプロンプト: 現在の todo 状態を含む
String system = "You are a coding agent at " + workDir + ".\n"
        + "Use the todo tool to plan multi-step tasks. "
        + "Mark in_progress before starting, completed when done.\n"
        + "IMPORTANT: You MUST call updateTodos regularly.\n\n"
        + "<current-todos>\n" + todoManager.render() + "\n</current-todos>";
```

「同時に in_progress は1つだけ」の制約が逐次的な集中を強制する。システムプロンプトへの todo 状態の継続的な注入が説明責任を生む -- モデルは毎回自分の計画を見るため、更新を忘れない。

> **TIP**: Python 版ではツールループ内で `rounds_since_todo` を追跡し、3ラウンド連続で todo を呼ばなかった場合に `<reminder>` テキストを注入する。Spring AI の ChatClient は内部でツールループを自動管理するため、ループ内での注入はできない。そのため、システムプロンプト注入方式で同等の効果を実現している。

## s02 からの変更点

| コンポーネント   | 変更前 (s02)     | 変更後 (s03)                         |
|----------------|------------------|--------------------------------------|
| Tools          | 4                | 5 (+TodoManager `@Tool`)             |
| 計画           | なし             | ステータス付き TodoManager            |
| 状態注入       | なし             | システムプロンプトに `<current-todos>` を注入 |
| ChatClient     | 固定システムプロンプト | 毎ターン再構築、動的に todo 状態を注入 |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s03.S03TodoWrite
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Refactor the file Hello.java: add JavaDoc, improve naming, and keep main method behavior unchanged`
2. `Create a Java package with utils and tests`
3. `Review all Java files and fix any style issues`
