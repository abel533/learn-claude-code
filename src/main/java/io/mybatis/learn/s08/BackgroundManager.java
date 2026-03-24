package io.mybatis.learn.s08;

import io.mybatis.learn.core.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 后台任务管理器 —— 慢操作丢后台，Agent 继续想下一步。
 * <p>
 * TIP: 对应 Python {@code agents/s08_background_tasks.py} 中的 {@code BackgroundManager} 类。
 * Python 使用 {@code threading.Thread(daemon=True)}，
 * Java 使用 {@link ExecutorService} + 虚拟线程（Java 21）。
 * <pre>
 *   Main thread              Background thread
 *   +-----------------+      +-----------------+
 *   | agent loop      |      | task executes   |
 *   | ...             |      | ...             |
 *   | [LLM call] <--+------- | notify(result)  |
 *   |  ^drain queue  |       +-----------------+
 *   +-----------------+
 * </pre>
 */
public class BackgroundManager {
    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);

    private static final int TIMEOUT_SECONDS = 300;

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final List<Notification> notificationQueue = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String workDir;

    record TaskInfo(String status, String result, String command) {
    }

    public record Notification(String taskId, String status, String command, String result) {
    }

    public BackgroundManager() {
        this.workDir = System.getProperty("user.dir");
        log.info("BackgroundManager 初始化，workDir={}", workDir);
    }

    @Tool(description = "Run a command in a background thread. Returns task_id immediately without waiting.")
    public String backgroundRun(
            @ToolParam(description = "The shell command to run in background") String command) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        tasks.put(taskId, new TaskInfo("running", null, command));
        log.info("后台任务已提交: taskId={}, command={}", taskId, command.substring(0, Math.min(80, command.length())));

        executor.submit(() -> execute(taskId, command));

        return "Background task " + taskId + " started: "
                + command.substring(0, Math.min(80, command.length()));
    }

    private void execute(String taskId, String command) {
        if (log.isDebugEnabled()) {
            System.out.printf("⏳ 后台任务 %s 开始执行%n", taskId);
        }
        String status;
        String output;
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output = "Error: Timeout (" + TIMEOUT_SECONDS + "s)";
                status = "timeout";
                log.warn("后台任务超时: taskId={}, timeout={}s", taskId, TIMEOUT_SECONDS);
            } else {
                output = output.trim();
                status = "completed";
                if (log.isDebugEnabled()) {
                    System.out.printf("✅ 后台任务 %s 执行完成%n", taskId);
                }
            }
        } catch (Exception e) {
            output = "Error: " + e.getMessage();
            status = "error";
            log.warn("后台任务执行异常: taskId={}, error={}", taskId, e.getMessage());
        }

        String finalOutput = AgentRunner.truncate(output.isEmpty() ? "(no output)" : output, 50000);
        tasks.put(taskId, new TaskInfo(status, finalOutput, command));
        log.info("后台任务状态更新: taskId={}, status={}", taskId, status);

        notificationQueue.add(new Notification(
                taskId, status,
                command.substring(0, Math.min(80, command.length())),
                finalOutput.substring(0, Math.min(500, finalOutput.length()))
        ));
    }

    @Tool(description = "Check background task status. Omit taskId to list all tasks.")
    public String checkBackground(
            @ToolParam(description = "Task ID to check (omit for all)", required = false) String taskId) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔍 查询后台任务: %s%n", taskId);
        }
        if (taskId != null && !taskId.isBlank()) {
            TaskInfo t = tasks.get(taskId);
            if (t == null) return "Error: Unknown task " + taskId;
            return "[" + t.status() + "] "
                    + t.command().substring(0, Math.min(60, t.command().length())) + "\n"
                    + (t.result() != null ? t.result() : "(running)");
        }
        if (tasks.isEmpty()) return "No background tasks.";
        StringBuilder sb = new StringBuilder();
        tasks.forEach((tid, t) ->
                sb.append(tid).append(": [").append(t.status()).append("] ")
                        .append(t.command(), 0, Math.min(60, t.command().length()))
                        .append("\n"));
        return sb.toString().stripTrailing();
    }

    /**
     * 排空通知队列，返回所有已完成的后台任务结果。
     * TIP: 对应 Python {@code BackgroundManager.drain_notifications()}。
     */
    public List<Notification> drainNotifications() {
        List<Notification> drained = new ArrayList<>(notificationQueue);
        notificationQueue.clear();
        if (!drained.isEmpty()) {
            if (log.isDebugEnabled()) {
                System.out.printf("📬 排空通知队列 (%d 条)%n", drained.size());
            }
        }
        return drained;
    }
}
