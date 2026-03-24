package io.mybatis.learn.s07;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 持久化任务管理器 —— 任务状态存储在磁盘上，不受上下文压缩影响。
 * <p>
 * TIP: 对应 Python {@code agents/s07_task_system.py} 中的 {@code TaskManager} 类。
 * Python 使用 {@code json.loads/json.dumps}，Java 使用 Jackson {@code ObjectMapper}。
 * <pre>
 *   .tasks/
 *     task_1.json  {"id":1, "subject":"...", "status":"completed", ...}
 *     task_2.json  {"id":2, "blockedBy":[1], "status":"pending", ...}
 *
 *   依赖解析:
 *     task 1 (complete) --> task 2 (blocked) --> task 3 (blocked)
 *       |                       ^
 *       +--- completing task 1 removes it from task 2's blockedBy
 * </pre>
 */
public class TaskManager {
    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VALID_STATUSES = List.of("pending", "in_progress", "completed");

    private final Path dir;
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        try {
            Files.createDirectories(dir);
            if (log.isDebugEnabled()) {
                System.out.printf("🚀 任务目录就绪: %s%n", dir);
            }
        } catch (IOException e) {
            log.error("创建任务目录失败: {}, error={}", dir, e.getMessage());
            throw new RuntimeException("Cannot create tasks directory: " + e.getMessage(), e);
        }
        this.nextId = maxId() + 1;
        log.info("TaskManager 初始化完成，nextId={}, dir={}", nextId, dir);
    }

    private int maxId() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(f -> {
                        String name = f.getFileName().toString();
                        return Integer.parseInt(name.substring(5, name.length() - 5));
                    })
                    .max().orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private Map<String, Object> load(int taskId) throws IOException {
        Path path = dir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Task " + taskId + " not found");
        }
        return MAPPER.readValue(Files.readString(path), new TypeReference<>() {
        });
    }

    private void save(Map<String, Object> task) throws IOException {
        Path path = dir.resolve("task_" + task.get("id") + ".json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
    }

    @Tool(description = "Create a new task with subject and optional description")
    public String taskCreate(
            @ToolParam(description = "Short subject of the task") String subject,
            @ToolParam(description = "Detailed description", required = false) String description) {
        if (log.isDebugEnabled()) {
            System.out.printf("📋 创建任务: %s%n", subject);
        }
        try {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", nextId);
            task.put("subject", subject);
            task.put("description", description != null ? description : "");
            task.put("status", "pending");
            task.put("blockedBy", new ArrayList<>());
            task.put("blocks", new ArrayList<>());
            task.put("owner", "");
            save(task);
            nextId++;
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 任务 #%s 已创建 (nextId=%d)%n", task.get("id"), nextId);
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (Exception e) {
            log.warn("任务创建失败: subject={}, error={}", subject, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get full details of a task by ID")
    public String taskGet(@ToolParam(description = "Task ID") int taskId) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔍 查询任务 #%d%n", taskId);
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(load(taskId));
        } catch (Exception e) {
            log.warn("查询任务详情失败: taskId={}, error={}", taskId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * TIP: 对应 Python {@code TaskManager.update()}。
     * 当 status 变为 "completed" 时自动清除依赖（调用 clearDependency）。
     * blockedBy/blocks 是双向关系：添加 blocks 时也更新被阻塞任务的 blockedBy。
     */
    @Tool(description = "Update a task's status or dependencies. "
            + "Status: pending/in_progress/completed. "
            + "Use addBlockedBy/addBlocks to manage dependency graph.")
    @SuppressWarnings("unchecked")
    public String taskUpdate(
            @ToolParam(description = "Task ID") int taskId,
            @ToolParam(description = "New status", required = false) String status,
            @ToolParam(description = "Task IDs that block this task", required = false) List<Integer> addBlockedBy,
            @ToolParam(description = "Task IDs that this task blocks", required = false) List<Integer> addBlocks) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔄 更新任务 #%d (status=%s, +blockedBy=%d, +blocks=%d)%n",
                    taskId, status, addBlockedBy == null ? 0 : addBlockedBy.size(), addBlocks == null ? 0 : addBlocks.size());
        }
        try {
            Map<String, Object> task = load(taskId);

            if (status != null) {
                if (!VALID_STATUSES.contains(status)) {
                    log.warn("非法状态更新: taskId={}, status={}", taskId, status);
                    return "Error: Invalid status: " + status;
                }
                task.put("status", status);
                if ("completed".equals(status)) {
                    clearDependency(taskId);
                }
            }

            if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
                List<Integer> current = (List<Integer>) task.get("blockedBy");
                Set<Integer> merged = new LinkedHashSet<>(current);
                merged.addAll(addBlockedBy);
                task.put("blockedBy", new ArrayList<>(merged));
            }

            if (addBlocks != null && !addBlocks.isEmpty()) {
                List<Integer> current = (List<Integer>) task.get("blocks");
                Set<Integer> merged = new LinkedHashSet<>(current);
                merged.addAll(addBlocks);
                task.put("blocks", new ArrayList<>(merged));
                // 双向关系：更新被阻塞任务的 blockedBy
                for (int blockedId : addBlocks) {
                    try {
                        Map<String, Object> blocked = load(blockedId);
                        List<Integer> blockedBy = (List<Integer>) blocked.get("blockedBy");
                        if (!blockedBy.contains(taskId)) {
                            blockedBy.add(taskId);
                            save(blocked);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            save(task);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 任务 #%d 已更新%n", taskId);
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (Exception e) {
            log.warn("任务更新失败: taskId={}, error={}", taskId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * TIP: 对应 Python {@code TaskManager._clear_dependency(completed_id)}。
     * 完成任务后，从所有其他任务的 blockedBy 列表中移除该任务 ID。
     */
    @SuppressWarnings("unchecked")
    private void clearDependency(int completedId) {
        if (log.isDebugEnabled()) {
            System.out.printf("💭 清理依赖关系: 已完成任务 #%d%n", completedId);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                    .forEach(f -> {
                        try {
                            Map<String, Object> task = MAPPER.readValue(
                                    Files.readString(f), new TypeReference<>() {
                                    });
                            List<Integer> blockedBy = (List<Integer>) task.get("blockedBy");
                            if (blockedBy != null && blockedBy.remove(Integer.valueOf(completedId))) {
                                save(task);
                                if (log.isDebugEnabled()) {
                                    System.out.printf("🔗 已解除依赖: #%d ← %s%n", completedId, f.getFileName());
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @Tool(description = "List all tasks with status summary")
    public String taskList() {
        if (log.isDebugEnabled()) {
            System.out.printf("📋 列出所有任务%n");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Map<String, Object>> tasks = files
                    .filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted()
                    .map(f -> {
                        try {
                            return MAPPER.readValue(Files.readString(f),
                                    new TypeReference<Map<String, Object>>() {
                                    });
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (tasks.isEmpty()) return "No tasks.";

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> t : tasks) {
                String marker = switch (String.valueOf(t.get("status"))) {
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[ ]";
                };
                sb.append(marker).append(" #").append(t.get("id")).append(": ").append(t.get("subject"));
                List<?> blockedBy = (List<?>) t.get("blockedBy");
                if (blockedBy != null && !blockedBy.isEmpty()) {
                    sb.append(" (blocked by: ").append(blockedBy).append(")");
                }
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            log.warn("列出任务失败: error={}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
