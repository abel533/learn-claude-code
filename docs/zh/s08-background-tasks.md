# s08: Background Tasks (后台任务)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > [ s08 ] s09 > s10 > s11 > s12`

> *"慢操作丢后台, agent 继续想下一步"* -- 后台线程跑命令, 完成后注入通知。
>
> **Harness 层**: 后台执行 -- 模型继续思考, harness 负责等待。

## 问题

有些命令要跑好几分钟: `npm install`、`pytest`、`docker build`。阻塞式循环下模型只能干等。用户说 "装依赖, 顺便建个配置文件", 智能体却只能一个一个来。

## 解决方案

```
Main thread                Background thread
+-----------------+        +-----------------+
| agent loop      |        | subprocess runs |
| ...             |        | ...             |
| [LLM call] <---+------- | enqueue(result) |
|  ^drain queue   |        +-----------------+
+-----------------+

Timeline:
Agent --[spawn A]--[spawn B]--[other work]----
             |          |
             v          v
          [A runs]   [B runs]      (parallel)
             |          |
             +-- results injected before next LLM call --+
```

## 工作原理

1. BackgroundManager 用线程安全的并发容器追踪任务。Java 使用 `ConcurrentHashMap` 和 `CopyOnWriteArrayList` 代替 Python 的手动加锁。

```java
public class BackgroundManager {
    private static final int TIMEOUT_SECONDS = 300;

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final List<Notification> notificationQueue = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    record TaskInfo(String status, String result, String command) {}
    public record Notification(String taskId, String status, String command, String result) {}
}
```

2. `backgroundRun()` 提交虚拟线程 (Java 21), 立即返回。相比 Python 的 `daemon=True` 线程，虚拟线程更轻量、由 JVM 调度。

```java
@Tool(description = "Run a command in a background thread. Returns task_id immediately without waiting.")
public String backgroundRun(
        @ToolParam(description = "The shell command to run in background") String command) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    tasks.put(taskId, new TaskInfo("running", null, command));

    executor.submit(() -> execute(taskId, command));

    return "Background task " + taskId + " started: "
            + command.substring(0, Math.min(80, command.length()));
}
```

3. 子进程完成后, 结果进入通知队列。使用 `ProcessBuilder` 执行命令，支持超时控制。

```java
private void execute(String taskId, String command) {
    String status, output;
    try {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); status = "timeout"; }
        else { status = "completed"; }
    } catch (Exception e) { output = "Error: " + e.getMessage(); status = "error"; }

    tasks.put(taskId, new TaskInfo(status, output, command));
    notificationQueue.add(new Notification(taskId, status, command, output));
}
```

4. 每次用户输入时排空通知队列, 注入系统提示。Spring AI 的 `ChatClient` 管理内部工具循环, 因此改为在每次用户输入时 drain 通知并构建系统提示, 核心概念不变: fire and forget。

```java
AgentRunner.interactive("s08", userMessage -> {
    // Drain 后台任务通知（对应 Python 中循环前的 drain_notifications）
    var notifs = bgManager.drainNotifications();
    String bgContext = "";
    if (!notifs.isEmpty()) {
        String notifText = notifs.stream()
                .map(n -> "[bg:" + n.taskId() + "] " + n.status() + ": " + n.result())
                .collect(Collectors.joining("\n"));
        bgContext = "\n\n<background-results>\n" + notifText + "\n</background-results>";
    }

    String system = "You are a coding agent. Use backgroundRun for long-running commands."
            + bgContext;

    ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), bgManager)
            .build();

    return chatClient.prompt().user(userMessage).call().content();
});
```

循环保持单线程。只有子进程 I/O 被并行化。

## 相对 s07 的变更

| 组件           | 之前 (s07)       | 之后 (s08)                         |
|----------------|------------------|------------------------------------|
| Tools          | 8                | 6 (基础 + backgroundRun + check)   |
| 执行方式       | 仅阻塞           | 阻塞 + 虚拟线程 (Java 21)          |
| 通知机制       | 无               | 每轮排空的 ConcurrentLinkedQueue    |
| 并发           | 无               | 虚拟线程 (更轻量, JVM 调度)         |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s08.S08BackgroundTasks
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Run "sleep 5 && echo done" in the background, then create a file while it runs`
2. `Start 3 background tasks: "sleep 2", "sleep 4", "sleep 6". Check their status.`
3. `Run pytest in the background and keep working on other things`
