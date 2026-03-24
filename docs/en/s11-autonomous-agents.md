# s11: Autonomous Agents

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12`

> *"Teammates scan the board and claim tasks themselves"* -- no need for the lead to assign each one. Self-organizing.
>
> **Harness layer**: Autonomy -- models that find work without being told.

## Problem

In s09-s10, teammates only work when explicitly told to. The lead must write a prompt for each teammate. 10 unclaimed tasks on the board? The lead assigns each one manually. Doesn't scale.

True autonomy: teammates scan the task board themselves, claim unclaimed tasks, work on them, then look for more.

One subtlety: after context compaction (s06), the agent might forget who it is. Identity re-injection fixes this.

## Solution

```
Teammate lifecycle with idle cycle:

+-------+
| spawn |
+---+---+
    |
    v
+-------+   tool_use     +-------+
| WORK  | <------------- |  LLM  |
+---+---+                +-------+
    |
    | stop_reason != tool_use (or idle tool called)
    v
+--------+
|  IDLE  |  poll every 5s for up to 60s
+---+----+
    |
    +---> check inbox --> message? ----------> WORK
    |
    +---> scan .tasks/ --> unclaimed? -------> claim -> WORK
    |
    +---> 60s timeout ----------------------> SHUTDOWN

Identity via system prompt (always present):
  ChatClient.builder(chatModel)
      .defaultSystem(identityPrompt)  // automatically included in every call
```

## How It Works

1. The teammate loop has two phases: WORK and IDLE. When the LLM stops calling tools (or calls `idle`), the teammate enters IDLE.

```java
// src/main/java/io/mybatis/learn/s11/S11AutonomousAgents.java
// AutonomousTeammateManager.autonomousLoop()

private void autonomousLoop(String name, String role, String initialPrompt) {
    // idle flag: set by tool call, detected by outer loop
    AtomicBoolean idleRequested = new AtomicBoolean(false);
    var idleTool = new IdleTool(idleRequested);

    ChatClient client = ChatClient.builder(chatModel)
            .defaultSystem(sysPrompt)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(),
                    messageTool, protocolTool, idleTool, claimTool)
            .build();

    while (true) {
        // -- WORK PHASE --
        String nextMsg = initialPrompt;
        for (int round = 0; round < 50 && nextMsg != null; round++) {
            var inbox = bus.readInbox(name);
            // ... merge inbox messages into nextMsg ...
            idleRequested.set(false);
            String response = client.prompt(sb.toString()).call().content();
            if (idleRequested.get()) break;  // idle tool was called
            nextMsg = null;  // subsequent rounds are inbox-driven
        }

        // -- IDLE PHASE --
        setStatus(name, "idle");
        // ... poll inbox + task board (see below) ...
        if (!resume) { setStatus(name, "shutdown"); return; }
        setStatus(name, "working");
    }
}
```

2. The idle phase polls inbox and task board in a loop.

```java
// IDLE PHASE: poll inbox + task board
setStatus(name, "idle");
boolean resume = false;
int polls = IDLE_TIMEOUT / Math.max(POLL_INTERVAL, 1);  // 60/5 = 12

for (int p = 0; p < polls; p++) {
    Thread.sleep(POLL_INTERVAL * 1000L);

    // Check inbox
    var inbox = bus.readInbox(name);
    if (!inbox.isEmpty()) {
        initialPrompt = "<inbox>" + mapper.writeValueAsString(inbox) + "</inbox>";
        resume = true;
        break;
    }

    // Scan task board
    var unclaimed = scanUnclaimedTasks(tasksDir);
    if (!unclaimed.isEmpty()) {
        var task = unclaimed.get(0);
        int taskId = ((Number) task.get("id")).intValue();
        claimTask(tasksDir, taskId, name);
        initialPrompt = String.format(
                "<auto-claimed>Task #%d: %s\n%s</auto-claimed>",
                taskId, task.get("subject"),
                task.getOrDefault("description", ""));
        resume = true;
        break;
    }
}

if (!resume) { setStatus(name, "shutdown"); return; }
setStatus(name, "working");
```

3. Task board scanning: find pending, unowned, unblocked tasks.

```java
static List<Map<String, Object>> scanUnclaimedTasks(Path tasksDir) {
    if (!Files.exists(tasksDir)) return List.of();
    List<Map<String, Object>> unclaimed = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    try (var files = Files.list(tasksDir)) {
        files.filter(f -> f.getFileName().toString().startsWith("task_")
                       && f.getFileName().toString().endsWith(".json"))
             .sorted()
             .forEach(f -> {
                 Map<String, Object> task = mapper.readValue(f.toFile(), Map.class);
                 if ("pending".equals(task.get("status"))
                     && (task.get("owner") == null || "".equals(task.get("owner")))
                     && (task.get("blockedBy") == null
                         || ((List<?>) task.get("blockedBy")).isEmpty())) {
                     unclaimed.add(task);
                 }
             });
    }
    return unclaimed;
}
```

4. Identity persistence: Java/Spring AI's `ChatClient.defaultSystem()` automatically includes the system prompt in every call, so identity is always present -- no need to manually re-inject after compaction as in the Python version.

```java
// Identity is injected via defaultSystem at build time, automatically included in every prompt
String sysPrompt = String.format(
        "You are '%s', role: %s, team: %s, at %s. "
        + "Use idle tool when you have no more work. You will auto-claim new tasks.",
        name, role, teamName, workDir);

ChatClient client = ChatClient.builder(chatModel)
        .defaultSystem(sysPrompt)  // Identity always present in system prompt
        .defaultTools(new BashTool(), new ReadFileTool(),
                new WriteFileTool(), new EditFileTool(),
                messageTool, protocolTool, idleTool, claimTool)
        .build();
```

## What Changed From s10

| Component      | Before (s10)     | After (s11)                      |
|----------------|------------------|----------------------------------|
| Tools          | 12               | 14 (+idle, +claim_task)          |
| Autonomy       | Lead-directed    | Self-organizing                  |
| Idle phase     | None             | Poll inbox + task board          |
| Task claiming  | Manual only      | Auto-claim unclaimed tasks       |
| Identity       | System prompt    | + re-injection after compaction  |
| Timeout        | None             | 60s idle -> auto shutdown        |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s11.S11AutonomousAgents
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Create 3 tasks on the board, then spawn alice and bob. Watch them auto-claim.`
2. `Spawn a coder teammate and let it find work from the task board itself`
3. `Create tasks with dependencies. Watch teammates respect the blocked order.`
4. Type `/tasks` to see the task board with owners
5. Type `/team` to monitor who is working vs idle
