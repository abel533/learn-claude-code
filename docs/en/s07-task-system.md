# s07: Task System

`s01 > s02 > s03 > s04 > s05 > s06 | [ s07 ] s08 > s09 > s10 > s11 > s12`

> *"Break big goals into small tasks, order them, persist to disk"* -- a file-based task graph with dependencies, laying the foundation for multi-agent collaboration.
>
> **Harness layer**: Persistent tasks -- goals that outlive any single conversation.

## Problem

s03's TodoManager is a flat checklist in memory: no ordering, no dependencies, no status beyond done-or-not. Real goals have structure -- task B depends on task A, tasks C and D can run in parallel, task E waits for both C and D.

Without explicit relationships, the agent can't tell what's ready, what's blocked, or what can run concurrently. And because the list lives only in memory, context compaction (s06) wipes it clean.

## Solution

Promote the checklist into a **task graph** persisted to disk. Each task is a JSON file with status, dependencies (`blockedBy`), and dependents (`blocks`). The graph answers three questions at any moment:

- **What's ready?** -- tasks with `pending` status and empty `blockedBy`.
- **What's blocked?** -- tasks waiting on unfinished dependencies.
- **What's done?** -- `completed` tasks, whose completion automatically unblocks dependents.

```
.tasks/
  task_1.json  {"id":1, "status":"completed"}
  task_2.json  {"id":2, "blockedBy":[1], "status":"pending"}
  task_3.json  {"id":3, "blockedBy":[1], "status":"pending"}
  task_4.json  {"id":4, "blockedBy":[2,3], "status":"pending"}

Task graph (DAG):
                 +----------+
            +--> | task 2   | --+
            |    | pending  |   |
+----------+     +----------+    +--> +----------+
| task 1   |                          | task 4   |
| completed| --> +----------+    +--> | blocked  |
+----------+     | task 3   | --+     +----------+
                 | pending  |
                 +----------+

Ordering:     task 1 must finish before 2 and 3
Parallelism:  tasks 2 and 3 can run at the same time
Dependencies: task 4 waits for both 2 and 3
Status:       pending -> in_progress -> completed
```

This task graph becomes the coordination backbone for everything after s07: background execution (s08), multi-agent teams (s09+), and worktree isolation (s12) all read from and write to this same structure.

## How It Works

1. **TaskManager**: one JSON file per task, CRUD with dependency graph. Uses Jackson `ObjectMapper` for JSON serialization.

```java
public class TaskManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path dir;
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        Files.createDirectories(dir);
        this.nextId = maxId() + 1;
    }

    @Tool(description = "Create a new task with subject and optional description")
    public String taskCreate(
            @ToolParam(description = "Short subject of the task") String subject,
            @ToolParam(description = "Detailed description", required = false) String description) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("status", "pending");
        task.put("blockedBy", new ArrayList<>());
        task.put("blocks", new ArrayList<>());
        save(task);
        nextId++;
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
    }
}
```

2. **Dependency resolution**: completing a task clears its ID from every other task's `blockedBy` list, automatically unblocking dependents.

```java
private void clearDependency(int completedId) {
    try (Stream<Path> files = Files.list(dir)) {
        files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                .forEach(f -> {
                    Map<String, Object> task = MAPPER.readValue(
                            Files.readString(f), new TypeReference<>() {});
                    List<Integer> blockedBy = (List<Integer>) task.get("blockedBy");
                    if (blockedBy != null && blockedBy.remove(Integer.valueOf(completedId))) {
                        save(task);
                    }
                });
    }
}
```

3. **Status transitions + dependency wiring**: `taskUpdate` handles status transitions and dependency edges. When status changes to `completed`, it automatically calls `clearDependency`; `blockedBy`/`blocks` are bidirectional relationships.

```java
@Tool(description = "Update a task's status or dependencies.")
public String taskUpdate(
        @ToolParam(description = "Task ID") int taskId,
        @ToolParam(description = "New status", required = false) String status,
        @ToolParam(description = "Task IDs that block this task", required = false) List<Integer> addBlockedBy,
        @ToolParam(description = "Task IDs that this task blocks", required = false) List<Integer> addBlocks) {
    Map<String, Object> task = load(taskId);
    if (status != null) {
        task.put("status", status);
        if ("completed".equals(status)) {
            clearDependency(taskId);
        }
    }
    // Handle addBlockedBy / addBlocks bidirectional dependencies ...
    save(task);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
}
```

4. **Spring AI auto-registers tools**: Pass `TaskManager` as a `defaultTools` argument to `ChatClient`. Spring AI automatically recognizes `@Tool` annotated methods -- no manual dispatch map needed.

```java
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S07TaskSystem implements CommandLineRunner {

    private final ChatClient chatClient;

    public S07TaskSystem(ChatModel chatModel) {
        Path tasksDir = Path.of(System.getProperty("user.dir"), ".tasks");
        TaskManager taskManager = new TaskManager(tasksDir);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent. Use task tools to plan and track work.")
                .defaultTools(
                        new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(),
                        taskManager   // @Tool methods in TaskManager are auto-registered
                )
                .build();
    }
}
```

From s07 onward, the task graph is the default for multi-step work. s03's Todo remains for quick single-session checklists.

## What Changed From s06

| Component | Before (s06) | After (s07) |
|---|---|---|
| Tools | 5 | 8 (`task_create/update/list/get`) |
| Planning model | Flat checklist (in-memory) | Task graph with dependencies (on disk) |
| Relationships | None | `blockedBy` + `blocks` edges |
| Status tracking | Done or not | `pending` -> `in_progress` -> `completed` |
| Persistence | Lost on compression | Survives compression and restarts |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s07.S07TaskSystem
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Create 3 tasks: "Setup project", "Write code", "Write tests". Make them depend on each other in order.`
2. `List all tasks and show the dependency graph`
3. `Complete task 1 and then list tasks to see task 2 unblocked`
4. `Create a task board for refactoring: parse -> transform -> emit -> test, where transform and emit can run in parallel after parse`
