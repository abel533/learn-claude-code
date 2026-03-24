# s11: Autonomous Agents (自治智能体)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12`

> *"队友自己看看板, 有活就认领"* -- 不需要领导逐个分配, 自组织。
>
> **Harness 层**: 自治 -- 模型自己找活干, 无需指派。

## 问题

s09-s10 中, 队友只在被明确指派时才动。领导得给每个队友写 prompt, 任务看板上 10 个未认领的任务得手动分配。这扩展不了。

真正的自治: 队友自己扫描任务看板, 认领没人做的任务, 做完再找下一个。

一个细节: 上下文压缩 (s06) 后智能体可能忘了自己是谁。身份重注入解决这个问题。

## 解决方案

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
      .defaultSystem(identityPrompt)  // 每次调用自动携带
```

## 工作原理

1. 队友循环分两个阶段: WORK 和 IDLE。LLM 停止调用工具 (或调用了 `idle`) 时, 进入 IDLE。

```java
// src/main/java/io/mybatis/learn/s11/S11AutonomousAgents.java
// AutonomousTeammateManager.autonomousLoop()

private void autonomousLoop(String name, String role, String initialPrompt) {
    // idle标志：工具调用时设置，外部循环检测
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
            // ... 合并收件箱消息到 nextMsg ...
            idleRequested.set(false);
            String response = client.prompt(sb.toString()).call().content();
            if (idleRequested.get()) break;  // idle工具被调用
            nextMsg = null;  // 后续轮次靠inbox驱动
        }

        // -- IDLE PHASE --
        setStatus(name, "idle");
        // ... 轮询收件箱 + 任务板（见下文） ...
        if (!resume) { setStatus(name, "shutdown"); return; }
        setStatus(name, "working");
    }
}
```

2. 空闲阶段循环轮询收件箱和任务看板。

```java
// IDLE PHASE: 轮询收件箱 + 任务板
setStatus(name, "idle");
boolean resume = false;
int polls = IDLE_TIMEOUT / Math.max(POLL_INTERVAL, 1);  // 60/5 = 12

for (int p = 0; p < polls; p++) {
    Thread.sleep(POLL_INTERVAL * 1000L);

    // 检查收件箱
    var inbox = bus.readInbox(name);
    if (!inbox.isEmpty()) {
        initialPrompt = "<inbox>" + mapper.writeValueAsString(inbox) + "</inbox>";
        resume = true;
        break;
    }

    // 扫描任务板
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

3. 任务看板扫描: 找 pending 状态、无 owner、未被阻塞的任务。

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

4. 身份保持: Java/Spring AI 的 `ChatClient.defaultSystem()` 在每次调用时自动携带系统提示, 身份信息始终存在, 无需像 Python 版本那样在压缩后手动重注入。

```java
// 身份信息通过 defaultSystem 在构建时注入, 每次 prompt 自动携带
String sysPrompt = String.format(
        "You are '%s', role: %s, team: %s, at %s. "
        + "Use idle tool when you have no more work. You will auto-claim new tasks.",
        name, role, teamName, workDir);

ChatClient client = ChatClient.builder(chatModel)
        .defaultSystem(sysPrompt)  // 身份始终存在于系统提示中
        .defaultTools(new BashTool(), new ReadFileTool(),
                new WriteFileTool(), new EditFileTool(),
                messageTool, protocolTool, idleTool, claimTool)
        .build();
```

## 相对 s10 的变更

| 组件           | 之前 (s10)       | 之后 (s11)                       |
|----------------|------------------|----------------------------------|
| Tools          | 12               | 14 (+idle, +claim_task)          |
| 自治性         | 领导指派         | 自组织                           |
| 空闲阶段       | 无               | 轮询收件箱 + 任务看板            |
| 任务认领       | 仅手动           | 自动认领未分配任务               |
| 身份           | 系统提示         | + 压缩后重注入                   |
| 超时           | 无               | 60 秒空闲 -> 自动关机            |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s11.S11AutonomousAgents
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Create 3 tasks on the board, then spawn alice and bob. Watch them auto-claim.`
2. `Spawn a coder teammate and let it find work from the task board itself`
3. `Create tasks with dependencies. Watch teammates respect the blocked order.`
4. 输入 `/tasks` 查看带 owner 的任务看板
5. 输入 `/team` 监控谁在工作、谁在空闲
