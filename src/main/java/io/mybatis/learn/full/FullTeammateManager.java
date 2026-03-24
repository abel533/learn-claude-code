package io.mybatis.learn.full;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.team.MessageBus;
import io.mybatis.learn.core.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 综合版 TeammateManager - 融合S09(团队) + S10(协议) + S11(自治) 全部特性
 *
 * TIPS: 对应Python s_full.py的TeammateManager（第400-543行）。
 * 特性汇总：
 *   S09: 虚拟线程 + JSONL收件箱通信
 *   S10: shutdown_request 检测 + 优雅退出
 *   S11: idle轮询 + 自动认领未分配任务
 *
 * Python队友执行50轮LLM调用的手动工具循环；
 * Java队友用ChatClient.prompt().call()自动处理工具循环，每次call()相当于Python整个while循环。
 */
public class FullTeammateManager {

    private static final int POLL_INTERVAL = 5;
    private static final int IDLE_TIMEOUT = 60;
    private static final int MAX_WORK_PHASES = 3;

    private final ChatModel chatModel;
    private final MessageBus bus;
    private final Path teamDir;
    private final Path configPath;
    private final Path tasksDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> config;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();

    public FullTeammateManager(ChatModel chatModel, MessageBus bus,
                               Path teamDir, Path tasksDir) {
        this.chatModel = chatModel;
        this.bus = bus;
        this.teamDir = teamDir;
        this.configPath = teamDir.resolve("config.json");
        this.tasksDir = tasksDir;
        try { Files.createDirectories(teamDir); } catch (IOException e) { throw new RuntimeException(e); }
        this.config = loadConfig();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        if (Files.exists(configPath)) {
            try { return mapper.readValue(configPath.toFile(), new TypeReference<>() {}); }
            catch (IOException e) { /* ignore */ }
        }
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("team_name", "default");
        def.put("members", new ArrayList<>());
        return def;
    }

    private synchronized void saveConfig() {
        try { mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config); }
        catch (IOException e) { System.err.println("Config save error: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void setStatus(String name, String status) {
        var members = (List<Map<String, Object>>) config.get("members");
        for (var m : members) {
            if (name.equals(m.get("name"))) m.put("status", status);
        }
        saveConfig();
    }

    // ---- 公开方法 ----

    @SuppressWarnings("unchecked")
    public String spawn(String name, String role, String prompt) {
        var members = (List<Map<String, Object>>) config.get("members");
        members.removeIf(m -> name.equals(m.get("name")));

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", name);
        member.put("role", role);
        member.put("status", "working");
        members.add(member);
        saveConfig();

        Thread t = Thread.startVirtualThread(() -> {
            try { teammateLoop(name, role, prompt); }
            catch (Exception e) {
                System.err.println("  [" + name + "] Fatal: " + e.getMessage());
                setStatus(name, "error");
            }
        });
        threads.put(name, t);
        return "Spawned '" + name + "' (role: " + role + ")";
    }

    @SuppressWarnings("unchecked")
    public String listAll() {
        var members = (List<Map<String, Object>>) config.get("members");
        if (members.isEmpty()) return "No teammates.";
        StringBuilder sb = new StringBuilder();
        for (var m : members) {
            sb.append(String.format("[%s] %s (role: %s)%n",
                    m.getOrDefault("status", "unknown"), m.get("name"), m.get("role")));
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    public List<String> memberNames() {
        var members = (List<Map<String, Object>>) config.get("members");
        return members.stream().map(m -> (String) m.get("name")).toList();
    }

    // ---- 队友主循环：工作 → 空闲 → 自动认领/恢复/关闭 ----

    private void teammateLoop(String name, String role, String initialPrompt) {
        String workDir = System.getProperty("user.dir");
        String sysPrompt = String.format(
                "You are '%s', role: %s, at %s. "
                        + "Send messages with send_message. Call idle when you have no more work. "
                        + "Call claim_task to pick up unassigned tasks from the shared board.",
                name, role, workDir);

        AtomicBoolean idleFlag = new AtomicBoolean(false);

        var msgTool = new TeammateMessageTool(bus, name);
        var idleTool = new IdleTool(idleFlag);
        var claimTool = new ClaimTaskTool(tasksDir, name);

        ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(sysPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(),
                        msgTool, idleTool, claimTool)
                .build();

        String nextInput = initialPrompt;

        for (int phase = 0; phase < MAX_WORK_PHASES; phase++) {
            // ---- 工作阶段 ----
            setStatus(name, "working");
            idleFlag.set(false);

            for (int round = 0; round < 50; round++) {
                // 检查收件箱
                var inbox = bus.readInbox(name);
                StringBuilder input = new StringBuilder();
                if (nextInput != null) { input.append(nextInput); nextInput = null; }

                boolean shutdownRequested = false;
                for (var msg : inbox) {
                    if ("shutdown_request".equals(msg.get("type"))) {
                        shutdownRequested = true;
                        bus.send(name, "lead", "Acknowledged shutdown.", "shutdown_response", null);
                    } else {
                        input.append("\n<inbox>").append(toJson(msg)).append("</inbox>");
                    }
                }

                if (shutdownRequested) {
                    System.out.println("  [" + name + "] Shutdown acknowledged.");
                    setStatus(name, "shutdown");
                    return;
                }

                String trimmedInput = input.toString().trim();
                if (trimmedInput.isEmpty()) {
                    if (round > 0) break; // 没有更多输入，结束工作阶段
                    continue;
                }

                try {
                    String response = client.prompt(trimmedInput).call().content();
                    System.out.println("  [" + name + "] " + AgentRunner.truncate(response, 120));
                } catch (Exception e) {
                    System.err.println("  [" + name + "] LLM error: " + e.getMessage());
                    break;
                }

                if (idleFlag.get()) break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }

            // ---- 空闲阶段 (S11) ----
            setStatus(name, "idle");
            System.out.println("  [" + name + "] Entering idle phase...");

            boolean resume = false;
            long deadline = System.currentTimeMillis() + IDLE_TIMEOUT * 1000L;

            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(POLL_INTERVAL * 1000L); } catch (InterruptedException e) { return; }

                // 轮询收件箱
                var inbox = bus.readInbox(name);
                if (!inbox.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    boolean shutdown = false;
                    for (var msg : inbox) {
                        if ("shutdown_request".equals(msg.get("type"))) {
                            shutdown = true;
                        } else {
                            sb.append("<inbox>").append(toJson(msg)).append("</inbox>\n");
                        }
                    }
                    if (shutdown) {
                        bus.send(name, "lead", "Acknowledged shutdown.", "shutdown_response", null);
                        setStatus(name, "shutdown");
                        return;
                    }
                    String inboxInput = sb.toString().trim();
                    if (!inboxInput.isEmpty()) {
                        nextInput = inboxInput;
                        resume = true;
                        break;
                    }
                }

                // 自动认领未分配任务 (S11)
                var unclaimed = scanUnclaimedTasks();
                if (!unclaimed.isEmpty()) {
                    var task = unclaimed.get(0);
                    claimTaskFile(task, name);
                    nextInput = "<auto-claimed>Task #" + task.get("id") + ": "
                            + task.get("subject") + "\n"
                            + task.getOrDefault("description", "") + "</auto-claimed>";
                    resume = true;
                    break;
                }
            }

            if (!resume) {
                System.out.println("  [" + name + "] Idle timeout. Shutting down.");
                setStatus(name, "shutdown");
                return;
            }
        }

        setStatus(name, "idle");
    }

    // ---- 辅助方法 ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scanUnclaimedTasks() {
        if (tasksDir == null || !Files.exists(tasksDir)) return List.of();
        try (var files = Files.list(tasksDir)) {
            List<Map<String, Object>> result = new ArrayList<>();
            files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                    .forEach(f -> {
                        try {
                            Map<String, Object> task = mapper.readValue(f.toFile(), new TypeReference<>() {});
                            if ("pending".equals(task.get("status"))
                                    && (task.get("owner") == null || task.get("owner").toString().isEmpty())) {
                                var blocked = (List<?>) task.getOrDefault("blockedBy", List.of());
                                if (blocked == null || blocked.isEmpty()) result.add(task);
                            }
                        } catch (IOException e) { /* skip */ }
                    });
            return result;
        } catch (IOException e) { return List.of(); }
    }

    private void claimTaskFile(Map<String, Object> task, String owner) {
        int taskId = ((Number) task.get("id")).intValue();
        task.put("owner", owner);
        task.put("status", "in_progress");
        task.put("updated_at", System.currentTimeMillis() / 1000.0);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    tasksDir.resolve("task_" + taskId + ".json").toFile(), task);
        } catch (IOException e) { /* ignore */ }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return obj.toString(); }
    }

    // ---- 参数化工具对象（每个队友独立实例） ----

    /**
     * TIPS: 队友的消息工具。每个实例绑定特定的发送者名称（sender identity）。
     * 对应Python中 TOOL_HANDLERS["send_message"] 和 TOOL_HANDLERS["read_inbox"]。
     */
    public static class TeammateMessageTool {
        private final MessageBus bus;
        private final String name;
        private final ObjectMapper mapper = new ObjectMapper();

        public TeammateMessageTool(MessageBus bus, String name) {
            this.bus = bus;
            this.name = name;
        }

        @Tool(description = "Send message to another teammate or lead")
        public String sendMessage(
                @ToolParam(description = "Recipient name") String to,
                @ToolParam(description = "Message content") String content) {
            return bus.send(name, to, content);
        }

        @Tool(description = "Read and drain your inbox")
        public String readInbox() {
            try { return mapper.writeValueAsString(bus.readInbox(name)); }
            catch (Exception e) { return "[]"; }
        }
    }

    /**
     * TIPS: 空闲信号工具。通过AtomicBoolean标志跨线程通信。
     * Python中idle()直接break循环；Java中ChatClient自动处理工具循环，
     * idle设置标志后，等call()返回再检查标志来退出外层循环。
     */
    public static class IdleTool {
        private final AtomicBoolean flag;

        public IdleTool(AtomicBoolean flag) { this.flag = flag; }

        @Tool(description = "Signal that you have completed current work and entering idle state")
        public String idle() {
            flag.set(true);
            return "Entering idle phase. Will poll for new tasks.";
        }
    }

    /**
     * TIPS: 任务认领工具。对应Python的claim_task（s_full第513-530行）。
     * 直接读写 .tasks/task_N.json 文件，将owner设为当前队友名。
     */
    public static class ClaimTaskTool {
        private final Path tasksDir;
        private final String owner;
        private final ObjectMapper mapper = new ObjectMapper();

        public ClaimTaskTool(Path tasksDir, String owner) {
            this.tasksDir = tasksDir;
            this.owner = owner;
        }

        @SuppressWarnings("unchecked")
        @Tool(description = "Claim an unclaimed task from the shared task board")
        public String claimTask(@ToolParam(description = "Task ID to claim") int taskId) {
            try {
                Path path = tasksDir.resolve("task_" + taskId + ".json");
                if (!Files.exists(path)) return "Error: Task " + taskId + " not found";
                Map<String, Object> task = mapper.readValue(path.toFile(), new TypeReference<>() {});
                if (!"pending".equals(task.get("status")))
                    return "Error: Task " + taskId + " is not pending (status: " + task.get("status") + ")";
                task.put("owner", owner);
                task.put("status", "in_progress");
                task.put("updated_at", System.currentTimeMillis() / 1000.0);
                mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), task);
                return "Claimed task " + taskId + ": " + task.get("subject");
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }
}
