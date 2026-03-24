# s12: Worktree + Task Isolation (Worktree 任务隔离)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > [ s12 ]`

> *"各干各的目录, 互不干扰"* -- 任务管目标, worktree 管目录, 按 ID 绑定。
>
> **Harness 层**: 目录隔离 -- 永不碰撞的并行执行通道。

## 问题

到 s11, 智能体已经能自主认领和完成任务。但所有任务共享一个目录。两个智能体同时重构不同模块 -- A 改 `Config.java`, B 也改 `Config.java`, 未提交的改动互相污染, 谁也没法干净回滚。

任务板管 "做什么" 但不管 "在哪做"。解法: 给每个任务一个独立的 git worktree 目录, 用任务 ID 把两边关联起来。

## 解决方案

```
Control plane (.tasks/)             Execution plane (.worktrees/)
+------------------+                +------------------------+
| task_1.json      |                | auth-refactor/         |
|   status: in_progress  <------>   branch: wt/auth-refactor
|   worktree: "auth-refactor"   |   task_id: 1             |
+------------------+                +------------------------+
| task_2.json      |                | ui-login/              |
|   status: pending    <------>     branch: wt/ui-login
|   worktree: "ui-login"       |   task_id: 2             |
+------------------+                +------------------------+
                                    |
                          index.json (worktree registry)
                          events.jsonl (lifecycle log)

State machines:
  Task:     pending -> in_progress -> completed
  Worktree: absent  -> active      -> removed | kept
```

## 工作原理

1. **创建任务。** 先把目标持久化。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeTaskManager.java
tasks.create("Implement auth refactor", "");
// -> .tasks/task_1.json  status=pending  worktree=""
```

2. **创建 worktree 并绑定任务。** 传入 `task_id` 自动将任务推进到 `in_progress`。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java
worktrees.create("auth-refactor", 1, "HEAD");
// -> git worktree add -b wt/auth-refactor .worktrees/auth-refactor HEAD
// -> index.json gets new entry, task_1.json gets worktree="auth-refactor"
```

绑定同时写入两侧状态:

```java
// src/main/java/io/mybatis/learn/s12/WorktreeTaskManager.java
public String bindWorktree(int taskId, String worktree, String owner) {
    var task = load(taskId);
    task.put("worktree", worktree);
    if (owner != null && !owner.isEmpty()) task.put("owner", owner);
    if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
    task.put("updated_at", System.currentTimeMillis() / 1000.0);
    save(task);
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
}
```

3. **在 worktree 中执行命令。** `cwd` 指向隔离目录。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java - run()
boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
ProcessBuilder pb = isWindows
        ? new ProcessBuilder("cmd", "/c", command)
        : new ProcessBuilder("sh", "-c", command);
pb.directory(path.toFile());
pb.redirectErrorStream(true);
Process p = pb.start();
String out = new String(p.getInputStream().readAllBytes()).trim();
boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
```

4. **收尾。** 两种选择:
   - `worktree_keep(name)` -- 保留目录供后续使用。
   - `worktree_remove(name, complete_task=True)` -- 删除目录, 完成绑定任务, 发出事件。一个调用搞定拆除 + 完成。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java
public String remove(String name, boolean force, boolean completeTask) {
    var wt = findWorktree(name);
    events.emit("worktree.remove.before", ...);
    runGit("worktree", "remove", wt.get("path").toString());
    if (completeTask && wt.get("task_id") != null) {
        int taskId = ((Number) wt.get("task_id")).intValue();
        tasks.update(taskId, "completed", null);
        tasks.unbindWorktree(taskId);
        events.emit("task.completed",
                Map.of("id", taskId, "status", "completed"),
                Map.of("name", name), null);
    }
    // 更新 index.json: status -> "removed"
}
```

5. **事件流。** 每个生命周期步骤写入 `.worktrees/events.jsonl`:

```json
{
  "event": "worktree.remove.after",
  "task": {"id": 1, "status": "completed"},
  "worktree": {"name": "auth-refactor", "status": "removed"},
  "ts": 1730000000
}
```

事件类型: `worktree.create.before/after/failed`, `worktree.remove.before/after/failed`, `worktree.keep`, `task.completed`。

崩溃后从 `.tasks/` + `.worktrees/index.json` 重建现场。会话记忆是易失的; 磁盘状态是持久的。

## 相对 s11 的变更

| 组件               | 之前 (s11)                 | 之后 (s12)                                   |
|--------------------|----------------------------|----------------------------------------------|
| 协调               | 任务板 (owner/status)      | 任务板 + worktree 显式绑定                   |
| 执行范围           | 共享目录                   | 每个任务独立目录                             |
| 可恢复性           | 仅任务状态                 | 任务状态 + worktree 索引                     |
| 收尾               | 任务完成                   | 任务完成 + 显式 keep/remove                  |
| 生命周期可见性     | 隐式日志                   | `.worktrees/events.jsonl` 显式事件流         |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s12.S12WorktreeIsolation
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Create tasks for backend auth and frontend login page, then list tasks.`
2. `Create worktree "auth-refactor" for task 1, then bind task 2 to a new worktree "ui-login".`
3. `Run "git status --short" in worktree "auth-refactor".`
4. `Keep worktree "ui-login", then list worktrees and inspect events.`
5. `Remove worktree "auth-refactor" with complete_task=true, then list tasks/worktrees/events.`
