# s06: Context Compact (上下文压缩)

`s01 > s02 > s03 > s04 > s05 > [ s06 ] | s07 > s08 > s09 > s10 > s11 > s12`

> *"上下文总会满, 要有办法腾地方"* -- 三层压缩策略, 换来无限会话。
>
> **Harness 层**: 压缩 -- 干净的记忆, 无限的会话。

## 问题

上下文窗口是有限的。读一个 1000 行的文件就吃掉 ~4000 token; 读 30 个文件、跑 20 条命令, 轻松突破 100k token。不压缩, 智能体根本没法在大项目里干活。

## 解决方案

三层压缩, 激进程度递增:

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

## 工作原理

1. **第一层 -- 上下文窗口管理**: Spring AI 的 ChatClient 自动管理工具循环, 无法在循环内插入压缩。Java 版通过限制注入系统提示的对话轮数（仅保留最近 N 轮）并截断内容来实现等价效果。

```java
/** 估算 token 数量: 粗略估计 4 字符 ≈ 1 token */
public int estimateTokens() {
    int chars = history.stream().mapToInt(t -> t.content().length()).sum();
    return chars / 4;
}

/** 获取对话历史的摘要（用于注入系统提示, 仅保留最近几轮） */
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

2. **第二层 -- auto_compact**: token 超过阈值时, 保存完整对话到磁盘, 让 LLM 做摘要。

```java
public String compact() {
    // 保存 transcript 到磁盘（完整历史不丢失）
    Files.createDirectories(transcriptDir);
    Path transcriptPath = transcriptDir.resolve(
            "transcript_" + System.currentTimeMillis() + ".jsonl");
    try (BufferedWriter writer = Files.newBufferedWriter(transcriptPath)) {
        for (ConversationTurn turn : history) {
            writer.write(objectMapper.writeValueAsString(turn));
            writer.newLine();
        }
    }

    // LLM 生成摘要
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

    // 用摘要替换历史
    history.clear();
    history.add(new ConversationTurn("system",
            "[Conversation compressed. Transcript: " + transcriptPath
                    + "]\n\n" + summary));
    return summary;
}
```

3. **第三层 -- manual compact**: `CompactTool` 工具按需触发同样的摘要机制。

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

4. REPL 层整合三层 (Spring AI 的 ChatClient 自动管理工具循环, 压缩在用户消息级别触发):

```java
AgentRunner.interactive("s06", userMessage -> {
    // Layer 2: 自动压缩检查（每次用户输入前）
    if (compactor.needsAutoCompact()) {
        System.out.println("[auto_compact triggered]");
        compactor.compact();
    }
    compactor.addTurn("user", userMessage);

    // 动态系统提示：包含对话上下文摘要
    String system = baseSystem + compactor.getContextSummary();
    ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), compactTool)
            .build();

    String response = chatClient.prompt()
            .user(userMessage).call().content();
    compactor.addTurn("assistant", response != null ? response : "");

    // Layer 3: 手动压缩（如果 Agent 调用了 compact 工具）
    if (compactor.isCompactRequested()) {
        compactor.compact();
    }
    return response;
});
```

完整历史通过 transcript 保存在磁盘上。信息没有真正丢失, 只是移出了活跃上下文。

## 相对 s05 的变更

| 组件           | 之前 (s05)       | 之后 (s06)                     |
|----------------|------------------|--------------------------------|
| Tools          | 5                | 5 (基础 + compact)             |
| 上下文管理     | 无               | 三层压缩                       |
| 上下文窗口管理 | 无               | 限制注入轮数 + 内容截断        |
| Auto-compact   | 无               | token 阈值触发                 |
| Transcripts    | 无               | 保存到 .transcripts/           |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s06.S06ContextCompact
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Read every Java file in the src/ directory one by one` (观察上下文窗口管理效果)
2. `Keep reading files until compression triggers automatically`
3. `Use the compact tool to manually compress the conversation`
