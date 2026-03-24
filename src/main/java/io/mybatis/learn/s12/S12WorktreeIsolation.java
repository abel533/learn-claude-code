package io.mybatis.learn.s12;

import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * S12 - Worktree + Task Isolation：目录级隔离的并行任务执行
 *
 * TIPS: 对应Python s12_worktree_task_isolation.py。
 * 与S09-S11的团队通信路线不同，S12走「目录隔离」路线：
 *   任务（控制平面）+ Worktree（执行平面）。
 *
 * S09-S11: 多个agent → 消息队列通信 → 协议协调
 * S12:     单个agent → 多个worktree → 按目录隔离 → 按task ID协调
 *
 * 17个工具：base(4) + task(5) + worktree(8)
 *
 * 关键洞察："Isolate by directory, coordinate by task ID."
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S12WorktreeIsolation implements CommandLineRunner {

    @Autowired
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        Path workDir = Path.of(System.getProperty("user.dir"));

        // 检测git仓库根目录
        Path repoRoot = detectRepoRoot(workDir);
        if (repoRoot == null) repoRoot = workDir;

        WorktreeTaskManager tasks = new WorktreeTaskManager(repoRoot.resolve(".tasks"));
        EventBus events = new EventBus(repoRoot.resolve(".worktrees").resolve("events.jsonl"));
        WorktreeManager worktrees = new WorktreeManager(repoRoot, tasks, events);

        TaskTools taskTools = new TaskTools(tasks);
        WorktreeTools wtTools = new WorktreeTools(worktrees, events);

        String systemPrompt = "You are a coding agent at " + workDir + ". "
                + "Use task + worktree tools for multi-task work. "
                + "For parallel or risky changes: create tasks, allocate worktree lanes, "
                + "run commands in those lanes, then choose keep/remove for closeout. "
                + "Use worktree_events when you need lifecycle visibility.";

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(),
                        taskTools, wtTools)
                .build();

        System.out.println("Repo root for s12: " + repoRoot);
        if (!worktrees.isGitAvailable()) {
            System.out.println("Note: Not in a git repo. worktree_* tools will return errors.");
        }

        AgentRunner.interactive("s12", input ->
                chatClient.prompt(input).call().content());
    }

    private static Path detectRepoRoot(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !out.isEmpty()) {
                Path root = Path.of(out);
                return root.toFile().exists() ? root : null;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    // ---- 任务工具集（5个） ----

    public static class TaskTools {
        private final WorktreeTaskManager tasks;

        public TaskTools(WorktreeTaskManager tasks) { this.tasks = tasks; }

        @Tool(description = "Create a new task on the shared task board")
        public String taskCreate(
                @ToolParam(description = "Task subject") String subject,
                @ToolParam(description = "Task description") String description) {
            return tasks.create(subject, description);
        }

        @Tool(description = "List all tasks with status, owner, and worktree binding")
        public String taskList() { return tasks.listAll(); }

        @Tool(description = "Get task details by ID")
        public String taskGet(@ToolParam(description = "Task ID") int taskId) {
            return tasks.get(taskId);
        }

        @Tool(description = "Update task status or owner")
        public String taskUpdate(
                @ToolParam(description = "Task ID") int taskId,
                @ToolParam(description = "New status: pending/in_progress/completed") String status,
                @ToolParam(description = "New owner name") String owner) {
            return tasks.update(taskId, status, owner);
        }

        @Tool(description = "Bind a task to a worktree name")
        public String taskBindWorktree(
                @ToolParam(description = "Task ID") int taskId,
                @ToolParam(description = "Worktree name") String worktree,
                @ToolParam(description = "Owner name") String owner) {
            return tasks.bindWorktree(taskId, worktree, owner);
        }
    }

    // ---- Worktree工具集（8个） ----

    /**
     * TIPS: 对应Python TOOL_HANDLERS中的8个worktree工具（s12第546-552行）。
     * 每个工具委托给WorktreeManager执行实际操作。
     * 安全检查：worktree名称正则验证（1-40字符），危险命令阻止。
     */
    public static class WorktreeTools {
        private final WorktreeManager worktrees;
        private final EventBus events;

        public WorktreeTools(WorktreeManager worktrees, EventBus events) {
            this.worktrees = worktrees;
            this.events = events;
        }

        @Tool(description = "Create a git worktree and optionally bind it to a task")
        public String worktreeCreate(
                @ToolParam(description = "Worktree name (1-40 chars: letters, numbers, ., _, -)") String name,
                @ToolParam(description = "Task ID to bind (optional)") Integer taskId,
                @ToolParam(description = "Base git ref (default: HEAD)") String baseRef) {
            return worktrees.create(name, taskId, baseRef);
        }

        @Tool(description = "List worktrees tracked in .worktrees/index.json")
        public String worktreeList() { return worktrees.listAll(); }

        @Tool(description = "Show git status for one worktree")
        public String worktreeStatus(
                @ToolParam(description = "Worktree name") String name) {
            return worktrees.status(name);
        }

        @Tool(description = "Run a shell command in a named worktree directory")
        public String worktreeRun(
                @ToolParam(description = "Worktree name") String name,
                @ToolParam(description = "Shell command to run") String command) {
            return worktrees.run(name, command);
        }

        @Tool(description = "Remove a worktree and optionally mark its bound task completed")
        public String worktreeRemove(
                @ToolParam(description = "Worktree name") String name,
                @ToolParam(description = "Force remove") boolean force,
                @ToolParam(description = "Mark bound task as completed") boolean completeTask) {
            return worktrees.remove(name, force, completeTask);
        }

        @Tool(description = "Mark a worktree as kept in lifecycle state without removing it")
        public String worktreeKeep(
                @ToolParam(description = "Worktree name") String name) {
            return worktrees.keep(name);
        }

        @Tool(description = "List recent worktree/task lifecycle events")
        public String worktreeEvents(
                @ToolParam(description = "Number of events to show (default 20)") int limit) {
            return events.listRecent(limit > 0 ? limit : 20);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(S12WorktreeIsolation.class, args);
    }
}
