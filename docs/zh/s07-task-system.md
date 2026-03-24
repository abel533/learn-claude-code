# s07: Task System (任务系统)

`s01 > s02 > s03 > s04 > s05 > s06 | [ s07 ] s08 > s09 > s10 > s11 > s12`

> *"大目标要拆成小任务, 排好序, 记在磁盘上"* -- 文件持久化的任务图, 为多 agent 协作打基础。
>
> **Harness 层**: 持久化任务 -- 比任何一次对话都长命的目标。

## 问题

s03 的 TodoManager 只是内存中的扁平清单: 没有顺序、没有依赖、状态只有做完没做完。真实目标是有结构的 -- 任务 B 依赖任务 A, 任务 C 和 D 可以并行, 任务 E 要等 C 和 D 都完成。

没有显式的关系, 智能体分不清什么能做、什么被卡住、什么能同时跑。而且清单只活在内存里, 上下文压缩 (s06) 一跑就没了。

## 解决方案

把扁平清单升级为持久化到磁盘的**任务图**。每个任务是一个 JSON 文件, 有状态、前置依赖 (`blockedBy`) 和后置依赖 (`blocks`)。任务图随时回答三个问题:

- **什么可以做?** -- 状态为 `pending` 且 `blockedBy` 为空的任务。
- **什么被卡住?** -- 等待前置任务完成的任务。
- **什么做完了?** -- 状态为 `completed` 的任务, 完成时自动解锁后续任务。

```
.tasks/
  task_1.json  {"id":1, "status":"completed"}
  task_2.json  {"id":2, "blockedBy":[1], "status":"pending"}
  task_3.json  {"id":3, "blockedBy":[1], "status":"pending"}
  task_4.json  {"id":4, "blockedBy":[2,3], "status":"pending"}

任务图 (DAG):
                 +----------+
            +--> | task 2   | --+
            |    | pending  |   |
+----------+     +----------+    +--> +----------+
| task 1   |                          | task 4   |
| completed| --> +----------+    +--> | blocked  |
+----------+     | task 3   | --+     +----------+
                 | pending  |
                 +----------+

顺序:   task 1 必须先完成, 才能开始 2 和 3
并行:   task 2 和 3 可以同时执行
依赖:   task 4 要等 2 和 3 都完成
状态:   pending -> in_progress -> completed
```

这个任务图是 s07 之后所有机制的协调骨架: 后台执行 (s08)、多 agent 团队 (s09+)、worktree 隔离 (s12) 都读写这同一个结构。

## 工作原理

1. **TaskManager**: 每个任务一个 JSON 文件, CRUD + 依赖图。使用 Jackson `ObjectMapper` 做 JSON 序列化。

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

2. **依赖解除**: 完成任务时, 自动将其 ID 从其他任务的 `blockedBy` 中移除, 解锁后续任务。

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

3. **状态变更 + 依赖关联**: `taskUpdate` 处理状态转换和依赖边。当 status 变为 `completed` 时自动调用 `clearDependency`；`blockedBy`/`blocks` 是双向关系。

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
    // 处理 addBlockedBy / addBlocks 双向依赖 ...
    save(task);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
}
```

4. **Spring AI 自动注册工具**: 将 `TaskManager` 作为 `defaultTools` 传入 `ChatClient`，Spring AI 自动识别 `@Tool` 注解方法，无需手动 dispatch map。

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
                        taskManager   // TaskManager 中的 @Tool 方法自动注册
                )
                .build();
    }
}
```

从 s07 起, 任务图是多步工作的默认选择。s03 的 Todo 仍可用于单次会话内的快速清单。

## 相对 s06 的变更

| 组件 | 之前 (s06) | 之后 (s07) |
|---|---|---|
| Tools | 5 | 8 (`task_create/update/list/get`) |
| 规划模型 | 扁平清单 (仅内存) | 带依赖关系的任务图 (磁盘) |
| 关系 | 无 | `blockedBy` + `blocks` 边 |
| 状态追踪 | 做完没做完 | `pending` -> `in_progress` -> `completed` |
| 持久化 | 压缩后丢失 | 压缩和重启后存活 |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s07.S07TaskSystem
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Create 3 tasks: "Setup project", "Write code", "Write tests". Make them depend on each other in order.`
2. `List all tasks and show the dependency graph`
3. `Complete task 1 and then list tasks to see task 2 unblocked`
4. `Create a task board for refactoring: parse -> transform -> emit -> test, where transform and emit can run in parallel after parse`
