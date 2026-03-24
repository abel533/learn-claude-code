# s06: Context Compact (コンテキスト圧縮)

`s01 > s02 > s03 > s04 > s05 > [ s06 ] | s07 > s08 > s09 > s10 > s11 > s12`

> *"コンテキストはいつか溢れる、空ける手段が要る"* -- 3層圧縮で無限セッションを実現。
>
> **Harness 層**: 圧縮 -- クリーンな記憶、無限のセッション。

## 問題

コンテキストウィンドウは有限だ。1000行のファイルを読むだけで約4000トークンを消費する。30ファイルを読み20回のコマンドを実行すると、100,000トークン超。圧縮なしでは、エージェントは大規模プロジェクトで作業できない。

## 解決策

積極性を段階的に上げる3層構成:

```
Every turn:
+------------------+
| Tool call result |
+------------------+
        |
        v
[Layer 1: micro_compact]        (silent, every turn)
  Replace tool_result > 3 turns old
  with "[Previous: used {tool_name}]"
        |
        v
[Check: tokens > 50000?]
   |               |
   no              yes
   |               |
   v               v
continue    [Layer 2: auto_compact]
              Save transcript to .transcripts/
              LLM summarizes conversation.
              Replace all messages with [summary].
                    |
                    v
            [Layer 3: compact tool]
              Model calls compact explicitly.
              Same summarization as auto_compact.
```

## 仕組み

1. **第1層 -- コンテキストウィンドウ管理**: Spring AI の ChatClient は内部でツールループを自動管理するため、ループ内に圧縮を挿入できない。Java 版では、システムプロンプトに注入する会話ターン数を制限し（最近の N ターンのみ保持）、コンテンツを切り詰めることで同等の効果を実現する。

```java
/** トークン数の推定: 粗い見積もりで 4文字 ≈ 1トークン */
public int estimateTokens() {
    int chars = history.stream().mapToInt(t -> t.content().length()).sum();
    return chars / 4;
}

/** 会話履歴のサマリーを取得（システムプロンプト注入用、最近数ターンのみ保持） */
public String getContextSummary() {
    if (history.isEmpty()) return "";
    StringBuilder sb = new StringBuilder("\n<conversation-context>\n");
    int start = Math.max(0, history.size() - KEEP_RECENT * 2);
    for (int i = start; i < history.size(); i++) {
        ConversationTurn turn = history.get(i);
        sb.append("[").append(turn.role()).append("]: ")
                .append(turn.content(), 0, Math.min(500, turn.content().length()))
                .append("\n");
    }
    sb.append("</conversation-context>");
    return sb.toString();
}
```

2. **第2層 -- auto_compact**: トークンが閾値を超えたら、完全な会話をディスクに保存し、LLM に要約を依頼する。

```java
public String compact() {
    // トランスクリプトをディスクに保存（完全な履歴は失われない）
    Files.createDirectories(transcriptDir);
    Path transcriptPath = transcriptDir.resolve(
            "transcript_" + System.currentTimeMillis() + ".jsonl");
    try (BufferedWriter writer = Files.newBufferedWriter(transcriptPath)) {
        for (ConversationTurn turn : history) {
            writer.write(objectMapper.writeValueAsString(turn));
            writer.newLine();
        }
    }

    // LLM が要約を生成
    String conversationText = history.stream()
            .map(t -> t.role() + ": " + t.content())
            .reduce("", (a, b) -> a + "\n" + b);
    if (conversationText.length() > 80000) {
        conversationText = conversationText.substring(0, 80000);
    }

    ChatClient summaryClient = ChatClient.builder(chatModel).build();
    String summary = summaryClient.prompt()
            .user("Summarize this conversation for continuity. Include: "
                    + "1) What was accomplished, 2) Current state, "
                    + "3) Key decisions.\n\n" + conversationText)
            .call().content();

    // 要約で履歴を置換
    history.clear();
    history.add(new ConversationTurn("system",
            "[Conversation compressed. Transcript: " + transcriptPath
                    + "]\n\n" + summary));
    return summary;
}
```

3. **第3層 -- manual compact**: `CompactTool` ツールが同じ要約メカニズムをオンデマンドでトリガーする。

```java
public class CompactTool {
    private final ContextCompactor compactor;

    public CompactTool(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    @Tool(description = "Trigger manual conversation compression to free up context space.")
    public String compact(
            @ToolParam(description = "What to preserve in summary",
                    required = false) String focus) {
        compactor.requestCompact();
        return "Compression triggered. Context will be summarized.";
    }
}
```

4. REPL 層が3層すべてを統合する（Spring AI の ChatClient が内部でツールループを自動管理するため、圧縮はユーザーメッセージレベルでトリガーされる）:

```java
AgentRunner.interactive("s06", userMessage -> {
    // Layer 2: 自動圧縮チェック（毎回のユーザー入力前）
    if (compactor.needsAutoCompact()) {
        System.out.println("[auto_compact triggered]");
        compactor.compact();
    }
    compactor.addTurn("user", userMessage);

    // 動的システムプロンプト: 会話コンテキストサマリーを含む
    String system = baseSystem + compactor.getContextSummary();
    ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), compactTool)
            .build();

    String response = chatClient.prompt()
            .user(userMessage).call().content();
    compactor.addTurn("assistant", response != null ? response : "");

    // Layer 3: 手動圧縮（Agent が compact ツールを呼び出した場合）
    if (compactor.isCompactRequested()) {
        compactor.compact();
    }
    return response;
});
```

完全な履歴はトランスクリプトとしてディスク上に保存される。情報は真に失われるのではなく、アクティブなコンテキストの外に移動されるだけだ。

## s05 からの変更点

| コンポーネント   | 変更前 (s05)     | 変更後 (s06)                   |
|----------------|------------------|--------------------------------|
| Tools          | 5                | 5 (基本 + compact)             |
| コンテキスト管理 | なし             | 三層圧縮                       |
| コンテキストウィンドウ管理 | なし   | 注入ターン数制限 + コンテンツ切り詰め |
| Auto-compact   | なし             | トークン閾値トリガー            |
| Transcripts    | なし             | .transcripts/ に保存           |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s06.S06ContextCompact
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Read every Java file in the src/ directory one by one` (コンテキストウィンドウ管理の効果を観察する)
2. `Keep reading files until compression triggers automatically`
3. `Use the compact tool to manually compress the conversation`
