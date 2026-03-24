# s08: Background Tasks

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > [ s08 ] s09 > s10 > s11 > s12`

> *"Run slow operations in the background; the agent keeps thinking"* -- background threads run commands, inject notifications on completion.
>
> **Harness layer**: Background execution -- the model thinks while the harness waits.

## Problem

Some commands take minutes: `npm install`, `pytest`, `docker build`. With a blocking loop, the model sits idle waiting. If the user asks "install dependencies and while that runs, create the config file," the agent does them sequentially, not in parallel.

## Solution

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

## How It Works

1. BackgroundManager tracks tasks with thread-safe concurrent containers. Java uses `ConcurrentHashMap` and `CopyOnWriteArrayList` instead of Python's manual locking.

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

2. `backgroundRun()` submits a virtual thread (Java 21) and returns immediately. Compared to Python's `daemon=True` threads, virtual threads are lighter and scheduled by the JVM.

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

3. When the subprocess finishes, the result goes into the notification queue. Uses `ProcessBuilder` for command execution with timeout control.

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

4. Drain the notification queue on each user input and inject into the system prompt. Spring AI's `ChatClient` manages the internal tool loop, so notifications are drained and built into the system prompt on each user input instead -- the core concept remains the same: fire and forget.

```java
AgentRunner.interactive("s08", userMessage -> {
    // Drain background task notifications (corresponds to Python's pre-loop drain_notifications)
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

The loop stays single-threaded. Only subprocess I/O is parallelized.

## What Changed From s07

| Component      | Before (s07)     | After (s08)                        |
|----------------|------------------|------------------------------------|
| Tools          | 8                | 6 (base + backgroundRun + check)   |
| Execution      | Blocking only    | Blocking + virtual threads (Java 21)|
| Notification   | None             | ConcurrentLinkedQueue drained per turn |
| Concurrency    | None             | Virtual threads (lighter, JVM-scheduled) |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s08.S08BackgroundTasks
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Run "sleep 5 && echo done" in the background, then create a file while it runs`
2. `Start 3 background tasks: "sleep 2", "sleep 4", "sleep 6". Check their status.`
3. `Run pytest in the background and keep working on other things`
