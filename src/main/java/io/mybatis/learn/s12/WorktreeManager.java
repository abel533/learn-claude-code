package io.mybatis.learn.s12;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Worktree管理器 - git worktree 创建/运行/删除 + 生命周期索引
 *
 * TIPS: 对应Python WorktreeManager类（s12第225-471行）。
 * Python用 subprocess.run(["git", ...]) 执行git命令；
 * Java用 ProcessBuilder 并检测 Windows/Unix 环境。
 * 索引文件 .worktrees/index.json 跟踪所有worktree的状态。
 */
public class WorktreeManager {
    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final Path repoRoot;
    private final WorktreeTaskManager tasks;
    private final EventBus events;
    private final Path worktreeDir;
    private final Path indexPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean gitAvailable;

    public WorktreeManager(Path repoRoot, WorktreeTaskManager tasks, EventBus events) {
        this.repoRoot = repoRoot;
        this.tasks = tasks;
        this.events = events;
        this.worktreeDir = repoRoot.resolve(".worktrees");
        this.indexPath = worktreeDir.resolve("index.json");
        try {
            Files.createDirectories(worktreeDir);
            if (!Files.exists(indexPath)) {
                Files.writeString(indexPath, "{\"worktrees\": []}");
            }
            if (log.isDebugEnabled()) {
                System.out.printf("🚀 worktree目录与索引就绪: dir=%s, index=%s%n", worktreeDir, indexPath);
            }
        } catch (IOException e) {
            log.error("初始化worktree目录失败: error={}", e.getMessage());
            throw new RuntimeException(e);
        }
        this.gitAvailable = isGitRepo();
        log.info("WorktreeManager 初始化完成，repoRoot={}, gitAvailable={}", repoRoot, gitAvailable);
    }

    private boolean isGitRepo() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) { return false; }
    }

    private String runGit(String... args) throws IOException {
        if (!gitAvailable) throw new IOException("Not in a git repository. worktree tools require git.");
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0) throw new IOException(out.isEmpty() ? "git " + String.join(" ", args) + " failed" : out);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ git 命令完成: %s%n", String.join(" ", args));
            }
            return out.isEmpty() ? "(no output)" : out;
        } catch (InterruptedException e) {
            throw new IOException("git command interrupted", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadIndex() throws IOException {
        return mapper.readValue(indexPath.toFile(), new TypeReference<>() {});
    }

    private void saveIndex(Map<String, Object> index) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findWorktree(String name) throws IOException {
        var index = loadIndex();
        for (var wt : (List<Map<String, Object>>) index.get("worktrees")) {
            if (name.equals(wt.get("name"))) return wt;
        }
        return null;
    }

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -");
        }
    }

    // ---- 创建 ----

    @SuppressWarnings("unchecked")
    public String create(String name, Integer taskId, String baseRef) {
        log.info("创建worktree请求: name={}, taskId={}, baseRef={}", name, taskId, baseRef);
        validateName(name);
        try {
            if (findWorktree(name) != null)
                return "Error: Worktree '" + name + "' already exists in index";
            if (taskId != null && !tasks.exists(taskId))
                return "Error: Task " + taskId + " not found";

            Path path = worktreeDir.resolve(name);
            String branch = "wt/" + name;
            String ref = baseRef != null ? baseRef : "HEAD";

            events.emit("worktree.create.before",
                    taskId != null ? Map.of("id", taskId) : Map.of(),
                    Map.of("name", name, "base_ref", ref), null);

            runGit("worktree", "add", "-b", branch, path.toString(), ref);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("path", path.toString());
            entry.put("branch", branch);
            entry.put("task_id", taskId);
            entry.put("status", "active");
            entry.put("created_at", System.currentTimeMillis() / 1000.0);

            var index = loadIndex();
            ((List<Map<String, Object>>) index.get("worktrees")).add(entry);
            saveIndex(index);

            if (taskId != null) tasks.bindWorktree(taskId, name, "");

            events.emit("worktree.create.after",
                    taskId != null ? Map.of("id", taskId) : Map.of(),
                    Map.of("name", name, "path", path.toString(),
                            "branch", branch, "status", "active"), null);
            log.info("创建worktree成功: name={}, branch={}, path={}", name, branch, path);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (Exception e) {
            log.warn("创建worktree失败: name={}, error={}", name, e.getMessage());
            events.emit("worktree.create.failed", Map.of(), Map.of("name", name), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ---- 列表 ----

    @SuppressWarnings("unchecked")
    public String listAll() {
        if (log.isDebugEnabled()) {
            System.out.printf("📋 列出所有worktree%n");
        }
        try {
            var index = loadIndex();
            var wts = (List<Map<String, Object>>) index.get("worktrees");
            if (wts.isEmpty()) return "No worktrees in index.";
            StringBuilder sb = new StringBuilder();
            for (var wt : wts) {
                String suffix = wt.get("task_id") != null ? " task=" + wt.get("task_id") : "";
                sb.append(String.format("[%s] %s -> %s (%s)%s%n",
                        wt.getOrDefault("status", "unknown"), wt.get("name"),
                        wt.get("path"), wt.getOrDefault("branch", "-"), suffix));
            }
            return sb.toString().trim();
        } catch (IOException e) {
            log.warn("列出worktree失败: error={}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ---- 状态 ----

    public String status(String name) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔍 查询worktree状态: %s%n", name);
        }
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path path = Path.of((String) wt.get("path"));
            if (!Files.exists(path)) return "Error: Worktree path missing: " + path;

            ProcessBuilder pb = new ProcessBuilder("git", "status", "--short", "--branch");
            pb.directory(path.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return out.isEmpty() ? "Clean worktree" : out;
        } catch (Exception e) {
            log.warn("查询worktree状态失败: name={}, error={}", name, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ---- 在worktree中运行命令 ----

    public String run(String name, String command) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔧 在 %s 中执行命令: %s%n", name,
                    command.substring(0, Math.min(80, command.length())));
        }
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
        for (String d : dangerous) {
            if (command.contains(d)) {
                log.warn("拦截worktree危险命令: name={}, pattern={}", name, d);
                return "Error: Dangerous command blocked";
            }
        }
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path path = Path.of((String) wt.get("path"));
            if (!Files.exists(path)) return "Error: Worktree path missing: " + path;

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.directory(path.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("worktree命令超时: name={}, timeout=300s", name);
                return "Error: Timeout (300s)";
            }
            return out.isEmpty() ? "(no output)" : out.substring(0, Math.min(out.length(), 50000));
        } catch (Exception e) {
            log.warn("执行worktree命令失败: name={}, error={}", name, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ---- 删除 ----

    @SuppressWarnings("unchecked")
    public String remove(String name, boolean force, boolean completeTask) {
        log.info("删除worktree请求: name={}, force={}, completeTask={}", name, force, completeTask);
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            events.emit("worktree.remove.before",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "path", wt.getOrDefault("path", "")), null);

            List<String> args = new ArrayList<>(List.of("worktree", "remove"));
            if (force) args.add("--force");
            args.add((String) wt.get("path"));
            runGit(args.toArray(String[]::new));

            if (completeTask && wt.get("task_id") != null) {
                int taskId = ((Number) wt.get("task_id")).intValue();
                tasks.update(taskId, "completed", null);
                tasks.unbindWorktree(taskId);
                events.emit("task.completed",
                        Map.of("id", taskId, "status", "completed"),
                        Map.of("name", name), null);
            }

            var index = loadIndex();
            for (var item : (List<Map<String, Object>>) index.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "removed");
                    item.put("removed_at", System.currentTimeMillis() / 1000.0);
                }
            }
            saveIndex(index);

            events.emit("worktree.remove.after",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "status", "removed"), null);
            log.info("删除worktree成功: name={}", name);

            return "Removed worktree '" + name + "'";
        } catch (Exception e) {
            log.warn("删除worktree失败: name={}, error={}", name, e.getMessage());
            events.emit("worktree.remove.failed", Map.of(), Map.of("name", name), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ---- 保留 ----

    @SuppressWarnings("unchecked")
    public String keep(String name) {
        log.info("保留worktree请求: name={}", name);
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            var index = loadIndex();
            Map<String, Object> kept = null;
            for (var item : (List<Map<String, Object>>) index.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "kept");
                    item.put("kept_at", System.currentTimeMillis() / 1000.0);
                    kept = item;
                }
            }
            saveIndex(index);

            events.emit("worktree.keep",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "status", "kept"), null);
            log.info("保留worktree成功: name={}", name);

            return kept != null
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(kept)
                    : "Error: Unknown worktree '" + name + "'";
        } catch (Exception e) {
            log.warn("保留worktree失败: name={}, error={}", name, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public boolean isGitAvailable() { return gitAvailable; }
}
