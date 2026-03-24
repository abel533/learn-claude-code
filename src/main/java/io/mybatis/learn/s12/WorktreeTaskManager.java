package io.mybatis.learn.s12;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Worktree任务管理器 - 持久化任务板 + worktree绑定
 *
 * TIPS: 对应Python TaskManager类（s12第122-217行）。
 * 与S07的TaskManager不同，此版本增加了 worktree 字段用于目录隔离绑定。
 * 任务数据结构：{id, subject, description, status, owner, worktree, blockedBy, created_at, updated_at}
 */
public class WorktreeTaskManager {
    private static final Logger log = LoggerFactory.getLogger(WorktreeTaskManager.class);

    private final Path tasksDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private int nextId;

    public WorktreeTaskManager(Path tasksDir) {
        this.tasksDir = tasksDir;
        try { Files.createDirectories(tasksDir); } catch (IOException e) {
            log.error("创建worktree任务目录失败: {}, error={}", tasksDir, e.getMessage());
            throw new RuntimeException(e);
        }
        this.nextId = maxId() + 1;
        log.info("WorktreeTaskManager 初始化完成，nextId={}, dir={}", nextId, tasksDir);
    }

    private int maxId() {
        try (var files = Files.list(tasksDir)) {
            return files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(f -> {
                        String name = f.getFileName().toString();
                        return Integer.parseInt(name.substring(5, name.length() - 5));
                    })
                    .max().orElse(0);
        } catch (IOException e) { return 0; }
    }

    private Path taskPath(int taskId) {
        return tasksDir.resolve("task_" + taskId + ".json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(int taskId) throws IOException {
        Path path = taskPath(taskId);
        if (!Files.exists(path)) throw new IOException("Task " + taskId + " not found");
        return mapper.readValue(path.toFile(), Map.class);
    }

    private void save(Map<String, Object> task) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                taskPath(((Number) task.get("id")).intValue()).toFile(), task);
    }

    public boolean exists(int taskId) {
        return Files.exists(taskPath(taskId));
    }

    public synchronized String create(String subject, String description) {
        if (log.isDebugEnabled()) {
            System.out.printf("📋 创建worktree任务: %s%n", subject);
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("worktree", "");
        task.put("blockedBy", new ArrayList<>());
        task.put("created_at", System.currentTimeMillis() / 1000.0);
        task.put("updated_at", System.currentTimeMillis() / 1000.0);
        try {
            save(task);
            nextId++;
            if (log.isDebugEnabled()) {
                System.out.printf("✅ worktree任务 #%s 已创建 (nextId=%d)%n", task.get("id"), nextId);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            log.warn("worktree任务创建失败: subject={}, error={}", subject, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String get(int taskId) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(load(taskId));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    public String update(int taskId, String status, String owner) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔄 更新worktree任务 #%d (status=%s, owner=%s)%n", taskId, status, owner);
        }
        try {
            var task = load(taskId);
            if (status != null) {
                if (!Set.of("pending", "in_progress", "completed").contains(status))
                    return "Error: Invalid status: " + status;
                task.put("status", status);
            }
            if (owner != null) task.put("owner", owner);
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ worktree任务 #%d 已更新%n", taskId);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            log.warn("worktree任务更新失败: taskId={}, error={}", taskId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 绑定worktree到任务（如果任务是pending则自动变为in_progress）
     */
    public String bindWorktree(int taskId, String worktree, String owner) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔗 绑定worktree: #%d → %s (owner=%s)%n", taskId, worktree, owner);
        }
        try {
            var task = load(taskId);
            task.put("worktree", worktree);
            if (owner != null && !owner.isEmpty()) task.put("owner", owner);
            if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            log.warn("绑定worktree失败: taskId={}, worktree={}, error={}", taskId, worktree, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String unbindWorktree(int taskId) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔗 解绑worktree: #%d%n", taskId);
        }
        try {
            var task = load(taskId);
            task.put("worktree", "");
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            log.warn("解绑worktree失败: taskId={}, error={}", taskId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    public String listAll() {
        try (var files = Files.list(tasksDir)) {
            List<Map<String, Object>> tasks = new ArrayList<>();
            files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                 .sorted()
                 .forEach(f -> {
                     try { tasks.add(mapper.readValue(f.toFile(), Map.class)); }
                     catch (IOException e) { /* skip */ }
                 });
            if (tasks.isEmpty()) return "No tasks.";
            StringBuilder sb = new StringBuilder();
            for (var t : tasks) {
                String status = (String) t.get("status");
                String marker = switch (status) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                String owner = t.get("owner") != null && !t.get("owner").toString().isEmpty()
                        ? " owner=" + t.get("owner") : "";
                String wt = t.get("worktree") != null && !t.get("worktree").toString().isEmpty()
                        ? " wt=" + t.get("worktree") : "";
                sb.append(String.format("%s #%s: %s%s%s%n",
                        marker, t.get("id"), t.get("subject"), owner, wt));
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}
