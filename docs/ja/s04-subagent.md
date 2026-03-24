# s04: Subagents (サブエージェント)

`s01 > s02 > s03 > [ s04 ] s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"大きなタスクを分割し、各サブタスクにクリーンなコンテキストを"* -- サブエージェントは独立した messages[] を使い、メイン会話を汚さない。
>
> **Harness 層**: コンテキスト隔離 -- モデルの思考の明晰さを守る。

## 問題

エージェントが作業するにつれ、messages 配列は膨張し続ける。すべてのファイル読み取り、すべてのコマンド出力がコンテキストに永久に残る。「このプロジェクトはどのテストフレームワークを使っているか」という質問は5つのファイルを読む必要があるかもしれないが、親エージェントに必要なのは「pytest」という一言だけだ。

## 解決策

```
Parent agent                     Subagent
+------------------+             +------------------+
| messages=[...]   |             | messages=[]      | <-- fresh
|                  |  dispatch   |                  |
| tool: task       | ----------> | while tool_use:  |
|   prompt="..."   |             |   call tools     |
|                  |  summary    |   append results |
|   result = "..." | <---------- | return last text |
+------------------+             +------------------+

Parent context stays clean. Subagent context is discarded.
```

## 仕組み

1. 親エージェントに `task` ツールを持たせる。子は `task` を除くすべての基本ツールを持つ（再帰的な生成は不可）。

```java
// 親 Agent: 基本ツール + SubagentTool を持つ
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent. "
                + "Use the task tool to delegate subtasks.")
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new SubagentTool(chatModel)  // 親 Agent 専用
        )
        .build();
```

2. サブエージェントは新しい `ChatClient` で起動し、独立したコンテキストを持つ。最終テキストだけが親に返る。

```java
@Tool(description = "Spawn a subagent with fresh context. "
        + "Use for exploration or subtasks that might pollute the main context.")
public String task(
        @ToolParam(description = "The task prompt") String prompt,
        @ToolParam(description = "Short description", required = false)
        String description) {

    // 新しい ChatClient を作成 -- これが「コンテキスト隔離」のすべて
    ChatClient subClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding subagent. "
                    + "Complete the task, then summarize findings.")
            .defaultTools(          // 基本ツール、task なし（再帰防止）
                    new BashTool(),
                    new ReadFileTool(),
                    new WriteFileTool(),
                    new EditFileTool()
            )
            .build();

    String result = subClient.prompt()
            .user(prompt)
            .call()
            .content();

    // 最終テキストだけを返し、子 Agent のコンテキストは破棄
    return (result != null) ? result : "(no summary)";
}
```

サブエージェントは複数回のツール呼び出しを実行するかもしれないが、メッセージ履歴全体は破棄される。親が受け取るのは要約テキストだけで、通常の `tool_result` として返される。Spring AI の `ChatClient.call()` が内部でツールループを管理するため、手動でイテレーション回数を制限する必要はない。

## s03 からの変更点

| コンポーネント   | 変更前 (s03)     | 変更後 (s04)                         |
|----------------|------------------|---------------------------------------|
| Tools          | 5                | 5 (基本) + SubagentTool (親側のみ)    |
| コンテキスト    | 単一共有         | 親 + 子隔離 (独立した ChatClient)     |
| Subagent       | なし             | `SubagentTool.task()` メソッド        |
| 戻り値         | 該当なし         | 要約テキストのみ                      |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s04.S04Subagent
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Use a subtask to find what testing framework this project uses`
2. `Delegate: read all .java files and summarize what each one does`
3. `Use a task to create a new module, then verify it from here`
