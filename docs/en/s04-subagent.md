# s04: Subagents

`s01 > s02 > s03 > [ s04 ] s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"Break big tasks down; each subtask gets a clean context"* -- subagents use independent messages[], keeping the main conversation clean.
>
> **Harness layer**: Context isolation -- protecting the model's clarity of thought.

## Problem

As the agent works, its messages array grows. Every file read, every bash output stays in context permanently. "What testing framework does this project use?" might require reading 5 files, but the parent only needs one word: "pytest."

## Solution

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

## How It Works

1. The parent agent has a `task` tool. The subagent gets all base tools except `task` (no recursive spawning).

```java
// Parent Agent: has base tools + SubagentTool
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent. "
                + "Use the task tool to delegate subtasks.")
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new SubagentTool(chatModel)  // Parent Agent exclusive
        )
        .build();
```

2. The subagent starts with a brand new `ChatClient` and an independent context. Only the final text returns to the parent.

```java
@Tool(description = "Spawn a subagent with fresh context. "
        + "Use for exploration or subtasks that might pollute the main context.")
public String task(
        @ToolParam(description = "The task prompt") String prompt,
        @ToolParam(description = "Short description", required = false)
        String description) {

    // Create a brand new ChatClient -- this IS "context isolation"
    ChatClient subClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding subagent. "
                    + "Complete the task, then summarize findings.")
            .defaultTools(          // Base tools, no task (prevents recursion)
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

    // Only the final text is returned; subagent context is discarded
    return (result != null) ? result : "(no summary)";
}
```

The subagent may have run multiple tool calls, but its entire message history is discarded. The parent receives only a summary text, returned as a normal `tool_result`. Spring AI's `ChatClient.call()` manages the tool loop internally -- no need to manually limit iteration count.

## What Changed From s03

| Component      | Before (s03)     | After (s04)                          |
|----------------|------------------|---------------------------------------|
| Tools          | 5                | 5 (base) + SubagentTool (parent only) |
| Context        | Single shared    | Parent + child isolation (independent ChatClient) |
| Subagent       | None             | `SubagentTool.task()` method          |
| Return value   | N/A              | Summary text only                     |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s04.S04Subagent
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Use a subtask to find what testing framework this project uses`
2. `Delegate: read all .java files and summarize what each one does`
3. `Use a task to create a new module, then verify it from here`
