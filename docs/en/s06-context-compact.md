# s06: Context Compact

`s01 > s02 > s03 > s04 > s05 > [ s06 ] | s07 > s08 > s09 > s10 > s11 > s12`

> *"Context will fill up; you need a way to make room"* -- three-layer compression strategy for infinite sessions.
>
> **Harness layer**: Compression -- clean memory for infinite sessions.

## Problem

The context window is finite. A single `read_file` on a 1000-line file costs ~4000 tokens; after reading 30 files and running 20 commands, you easily blow past 100k tokens. Without compression, the agent simply cannot work on large codebases.

## Solution

Three layers, increasing in aggressiveness:

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

## How It Works

1. **Layer 1 -- Context window management**: Spring AI's ChatClient manages the tool loop automatically and doesn't allow mid-loop compression injection. The Java version achieves an equivalent effect by limiting the number of conversation turns injected into the system prompt (keeping only the most recent N turns) and truncating content.

```java
/** Estimate token count: rough estimate of 4 chars ≈ 1 token */
public int estimateTokens() {
    int chars = history.stream().mapToInt(t -> t.content().length()).sum();
    return chars / 4;
}

/** Get conversation history summary (for system prompt injection, keeping only recent turns) */
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

2. **Layer 2 -- auto_compact**: When tokens exceed the threshold, save the full conversation to disk and have the LLM summarize it.

```java
public String compact() {
    // Save transcript to disk (full history is not lost)
    Files.createDirectories(transcriptDir);
    Path transcriptPath = transcriptDir.resolve(
            "transcript_" + System.currentTimeMillis() + ".jsonl");
    try (BufferedWriter writer = Files.newBufferedWriter(transcriptPath)) {
        for (ConversationTurn turn : history) {
            writer.write(objectMapper.writeValueAsString(turn));
            writer.newLine();
        }
    }

    // LLM generates summary
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

    // Replace history with summary
    history.clear();
    history.add(new ConversationTurn("system",
            "[Conversation compressed. Transcript: " + transcriptPath
                    + "]\n\n" + summary));
    return summary;
}
```

3. **Layer 3 -- manual compact**: The `CompactTool` triggers the same summarization mechanism on demand.

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

4. The REPL layer integrates all three layers (Spring AI's ChatClient manages the tool loop automatically; compression is triggered at the user message level):

```java
AgentRunner.interactive("s06", userMessage -> {
    // Layer 2: Auto-compact check (before each user input)
    if (compactor.needsAutoCompact()) {
        System.out.println("[auto_compact triggered]");
        compactor.compact();
    }
    compactor.addTurn("user", userMessage);

    // Dynamic system prompt: includes conversation context summary
    String system = baseSystem + compactor.getContextSummary();
    ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), compactTool)
            .build();

    String response = chatClient.prompt()
            .user(userMessage).call().content();
    compactor.addTurn("assistant", response != null ? response : "");

    // Layer 3: Manual compact (if the agent called the compact tool)
    if (compactor.isCompactRequested()) {
        compactor.compact();
    }
    return response;
});
```

Full history is preserved on disk via transcripts. Nothing is truly lost -- just moved out of active context.

## What Changed From s05

| Component      | Before (s05)     | After (s06)                    |
|----------------|------------------|--------------------------------|
| Tools          | 5                | 5 (base + compact)             |
| Context mgmt   | None             | Three-layer compression        |
| Context window mgmt | None        | Limited turn injection + content truncation |
| Auto-compact   | None             | Token threshold trigger        |
| Transcripts    | None             | Saved to .transcripts/         |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s06.S06ContextCompact
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Read every Java file in the src/ directory one by one` (observe context window management)
2. `Keep reading files until compression triggers automatically`
3. `Use the compact tool to manually compress the conversation`
