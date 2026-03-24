package io.mybatis.learn.s11;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.team.MessageBus;
import io.mybatis.learn.core.tools.*;
import io.mybatis.learn.s10.ProtocolTracker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S11 - Autonomous Agents：自主agent + 空闲轮询 + 任务认领
 *
 * TIPS: 对应Python s11_autonomous_agents.py。
 * 在S10基础上新增：
 *   1. idle工具 - 队友主动进入空闲阶段
 *   2. 空闲轮询 - 每5秒检查收件箱和任务板
 *   3. 自动认领 - 发现未分配任务时自动claim
 *   4. 身份重注入 - 上下文压缩后恢复身份信息
 *
 * 队友生命周期：
 *   spawn → WORK → idle → IDLE POLL → (message|task) → WORK → ...
 *   IDLE超时(60s) → shutdown
 *
 * 关键洞察："The agent finds work itself."
 */
@SpringBootApplication(scanBasePackages = {"io.mybatis.learn.core", "io.mybatis.learn.s10"})
public class S11AutonomousAgents implements CommandLineRunner {

    private static final int POLL_INTERVAL = 5;
    private static final int IDLE_TIMEOUT = 60;

    @Autowired
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        Path workDir = Path.of(System.getProperty("user.dir"));
        Path teamDir = workDir.resolve(".team");
        Path tasksDir = workDir.resolve(".tasks");

        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        ProtocolTracker tracker = new ProtocolTracker(bus);
        AutonomousTeammateManager team = new AutonomousTeammateManager(
                chatModel, bus, tracker, teamDir, tasksDir);
        AutonomousLeadTools leadTools = new AutonomousLeadTools(bus, team, tracker, tasksDir);
        ObjectMapper mapper = new ObjectMapper();

        String systemPrompt = "You are a team lead at " + workDir
                + ". Teammates are autonomous -- they find work themselves.";

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(), leadTools)
                .build();

        AgentRunner.interactive("s11", input -> {
            if ("/team".equals(input)) return team.listAll();
            if ("/inbox".equals(input)) {
                try { return mapper.writeValueAsString(bus.readInbox("lead")); }
                catch (Exception e) { return "Error: " + e.getMessage(); }
            }

            var inbox = bus.readInbox("lead");
            String fullInput = input;
            if (!inbox.isEmpty()) {
                try { fullInput = "<inbox>" + mapper.writeValueAsString(inbox) + "</inbox>\n\n" + input; }
                catch (Exception e) { /* ignore */ }
            }
            return chatClient.prompt(fullInput).call().content();
        });
    }

    // ---- 任务板扫描（全局函数） ----

    /**
     * TIPS: 对应Python scan_unclaimed_tasks()（s11第127-136行）。
     * 扫描 .tasks/task_*.json，找到 status=pending 且无 owner 的任务。
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> scanUnclaimedTasks(Path tasksDir) {
        if (!Files.exists(tasksDir)) return List.of();
        List<Map<String, Object>> unclaimed = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (var files = Files.list(tasksDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("task_") &&
                              f.getFileName().toString().endsWith(".json"))
                 .sorted()
                 .forEach(f -> {
                     try {
                         Map<String, Object> task = mapper.readValue(f.toFile(), Map.class);
                         if ("pending".equals(task.get("status"))
                             && (task.get("owner") == null || "".equals(task.get("owner")))
                             && (task.get("blockedBy") == null
                                 || ((List<?>) task.get("blockedBy")).isEmpty())) {
                             unclaimed.add(task);
                         }
                     } catch (IOException e) { /* skip */ }
                 });
        } catch (IOException e) { /* ignore */ }
        return unclaimed;
    }

    /**
     * TIPS: 对应Python claim_task()（s11第139-148行）。
     * 原子操作：标记任务被认领，使用 synchronized 保证互斥。
     */
    @SuppressWarnings("unchecked")
    static synchronized String claimTask(Path tasksDir, int taskId, String owner) {
        Path path = tasksDir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) return "Error: Task " + taskId + " not found";
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> task = mapper.readValue(path.toFile(), Map.class);
            task.put("owner", owner);
            task.put("status", "in_progress");
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), task);
            return "Claimed task #" + taskId + " for " + owner;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---- 自治队友管理器 ----

    /**
     * TIPS: 对应Python TeammateManager（s11第160-367行）。
     * 核心差异：双阶段循环（WORK → IDLE）。
     *
     * Python: WORK阶段逐次LLM调用（range(50)），idle工具触发进入IDLE阶段；
     *   IDLE阶段每5秒轮询收件箱+任务板，超时60秒→shutdown。
     * Java: ChatClient.prompt().call()自动完成WORK阶段的整个工具链，
     *   idle工具通过AtomicBoolean标志通知外部循环进入IDLE阶段。
     */
    static class AutonomousTeammateManager {
        private final ChatModel chatModel;
        private final MessageBus bus;
        private final ProtocolTracker tracker;
        private final Path configPath;
        private final Path tasksDir;
        private final ObjectMapper mapper = new ObjectMapper();
        private Map<String, Object> config;

        AutonomousTeammateManager(ChatModel chatModel, MessageBus bus,
                                  ProtocolTracker tracker, Path teamDir, Path tasksDir) {
            this.chatModel = chatModel;
            this.bus = bus;
            this.tracker = tracker;
            this.tasksDir = tasksDir;
            this.configPath = teamDir.resolve("config.json");
            try { Files.createDirectories(teamDir); } catch (IOException e) { throw new RuntimeException(e); }
            this.config = loadConfig();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> loadConfig() {
            if (Files.exists(configPath)) {
                try { return mapper.readValue(configPath.toFile(), Map.class); }
                catch (IOException e) { /* ignore */ }
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("team_name", "default");
            cfg.put("members", new ArrayList<>());
            return cfg;
        }

        private synchronized void saveConfig() {
            try { mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config); }
            catch (IOException e) { /* ignore */ }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> findMember(String name) {
            for (Map<String, Object> m : (List<Map<String, Object>>) config.get("members")) {
                if (name.equals(m.get("name"))) return m;
            }
            return null;
        }

        private synchronized void setStatus(String name, String status) {
            Map<String, Object> member = findMember(name);
            if (member != null) { member.put("status", status); saveConfig(); }
        }

        @SuppressWarnings("unchecked")
        public synchronized String spawn(String name, String role, String prompt) {
            Map<String, Object> member = findMember(name);
            if (member != null) {
                String status = (String) member.get("status");
                if (!"idle".equals(status) && !"shutdown".equals(status))
                    return "Error: '" + name + "' is currently " + status;
                member.put("status", "working");
                member.put("role", role);
            } else {
                member = new LinkedHashMap<>();
                member.put("name", name);
                member.put("role", role);
                member.put("status", "working");
                ((List<Map<String, Object>>) config.get("members")).add(member);
            }
            saveConfig();
            Thread.startVirtualThread(() -> autonomousLoop(name, role, prompt));
            return "Spawned '" + name + "' (role: " + role + ")";
        }

        /**
         * 自治循环：WORK → IDLE → WORK → ... → shutdown
         */
        private void autonomousLoop(String name, String role, String initialPrompt) {
            String teamName = (String) config.get("team_name");
            String workDir = System.getProperty("user.dir");
            String sysPrompt = String.format(
                    "You are '%s', role: %s, team: %s, at %s. "
                    + "Use idle tool when you have no more work. You will auto-claim new tasks.",
                    name, role, teamName, workDir);

            // idle标志：工具调用时设置，外部循环检测
            AtomicBoolean idleRequested = new AtomicBoolean(false);

            var messageTool = new io.mybatis.learn.s09.TeammateManager.TeammateMessageTool(bus, name);
            var protocolTool = new io.mybatis.learn.s10.S10TeamProtocols.TeammateProtocolTool(bus, tracker, name);
            var idleTool = new IdleTool(idleRequested);
            var claimTool = new ClaimTaskTool(tasksDir, name);

            ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem(sysPrompt)
                    .defaultTools(new BashTool(), new ReadFileTool(),
                            new WriteFileTool(), new EditFileTool(),
                            messageTool, protocolTool, idleTool, claimTool)
                    .build();

            try {
                while (true) {
                    // -- WORK PHASE --
                    String nextMsg = initialPrompt;
                    for (int round = 0; round < 50 && nextMsg != null; round++) {
                        // 检查收件箱
                        var inbox = bus.readInbox(name);
                        StringBuilder sb = new StringBuilder(nextMsg);
                        for (var msg : inbox) {
                            if ("shutdown_request".equals(msg.get("type"))) {
                                setStatus(name, "shutdown");
                                return;
                            }
                            sb.append("\n").append(mapper.writeValueAsString(msg));
                        }

                        idleRequested.set(false);
                        String response = client.prompt(sb.toString()).call().content();
                        System.out.println("  [" + name + "] "
                                + AgentRunner.truncate(response, 120));

                        if (idleRequested.get()) break;
                        nextMsg = null; // 后续轮次靠inbox驱动

                        Thread.sleep(1000);
                        var newInbox = bus.readInbox(name);
                        if (newInbox.isEmpty()) break;
                        nextMsg = "<inbox>" + mapper.writeValueAsString(newInbox) + "</inbox>";
                    }

                    // -- IDLE PHASE: 轮询收件箱 + 任务板 --
                    setStatus(name, "idle");
                    boolean resume = false;
                    int polls = IDLE_TIMEOUT / Math.max(POLL_INTERVAL, 1);

                    for (int p = 0; p < polls; p++) {
                        Thread.sleep(POLL_INTERVAL * 1000L);

                        // 检查收件箱
                        var inbox = bus.readInbox(name);
                        if (!inbox.isEmpty()) {
                            for (var msg : inbox) {
                                if ("shutdown_request".equals(msg.get("type"))) {
                                    setStatus(name, "shutdown");
                                    return;
                                }
                            }
                            initialPrompt = "<inbox>" + mapper.writeValueAsString(inbox) + "</inbox>";
                            resume = true;
                            break;
                        }

                        // 扫描任务板
                        var unclaimed = scanUnclaimedTasks(tasksDir);
                        if (!unclaimed.isEmpty()) {
                            var task = unclaimed.get(0);
                            int taskId = ((Number) task.get("id")).intValue();
                            claimTask(tasksDir, taskId, name);
                            initialPrompt = String.format(
                                    "<auto-claimed>Task #%d: %s\n%s</auto-claimed>",
                                    taskId, task.get("subject"),
                                    task.getOrDefault("description", ""));
                            resume = true;
                            break;
                        }
                    }

                    if (!resume) {
                        setStatus(name, "shutdown");
                        return;
                    }
                    setStatus(name, "working");
                }
            } catch (Exception e) {
                System.err.println("  [" + name + "] Error: " + e.getMessage());
                setStatus(name, "shutdown");
            }
        }

        @SuppressWarnings("unchecked")
        public String listAll() {
            List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
            if (members.isEmpty()) return "No teammates.";
            StringBuilder sb = new StringBuilder("Team: " + config.get("team_name"));
            for (Map<String, Object> m : members)
                sb.append("\n  ").append(m.get("name"))
                  .append(" (").append(m.get("role")).append("): ").append(m.get("status"));
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        public List<String> memberNames() {
            return ((List<Map<String, Object>>) config.get("members")).stream()
                    .map(m -> (String) m.get("name")).toList();
        }
    }

    // ---- idle工具 ----

    /**
     * TIPS: 对应Python idle工具（s11第352-353行）。
     * Python在_exec中直接返回字符串并设置idle_requested标志；
     * Java通过AtomicBoolean实现跨线程通信：
     *   工具执行时设置标志 → ChatClient完成工具链后 → 外部循环检测标志。
     */
    public static class IdleTool {
        private final AtomicBoolean idleFlag;

        public IdleTool(AtomicBoolean idleFlag) {
            this.idleFlag = idleFlag;
        }

        @Tool(description = "Signal that you have no more work. Enters idle polling phase.")
        public String idle() {
            idleFlag.set(true);
            return "Entering idle phase. Will poll for new tasks.";
        }
    }

    // ---- claim_task工具 ----

    public static class ClaimTaskTool {
        private final Path tasksDir;
        private final String owner;

        public ClaimTaskTool(Path tasksDir, String owner) {
            this.tasksDir = tasksDir;
            this.owner = owner;
        }

        @Tool(description = "Claim a task from the task board by ID")
        public String claimTask(
                @ToolParam(description = "Task ID to claim") int taskId) {
            return S11AutonomousAgents.claimTask(tasksDir, taskId, owner);
        }
    }

    // ---- Lead工具集（14个工具 = S10的12个 + idle + claim_task） ----

    public static class AutonomousLeadTools {
        private final MessageBus bus;
        private final AutonomousTeammateManager team;
        private final ProtocolTracker tracker;
        private final Path tasksDir;
        private final ObjectMapper mapper = new ObjectMapper();

        public AutonomousLeadTools(MessageBus bus, AutonomousTeammateManager team,
                                   ProtocolTracker tracker, Path tasksDir) {
            this.bus = bus;
            this.team = team;
            this.tracker = tracker;
            this.tasksDir = tasksDir;
        }

        @Tool(description = "Spawn an autonomous teammate") public String spawnTeammate(String name, String role, String prompt) { return team.spawn(name, role, prompt); }
        @Tool(description = "List all teammates") public String listTeammates() { return team.listAll(); }
        @Tool(description = "Send message to teammate") public String sendMessage(String to, String content) { return bus.send("lead", to, content); }
        @Tool(description = "Read lead's inbox") public String readInbox() { try { return mapper.writeValueAsString(bus.readInbox("lead")); } catch (Exception e) { return "[]"; } }
        @Tool(description = "Broadcast to all teammates") public String broadcast(String content) { return bus.broadcast("lead", content, team.memberNames()); }
        @Tool(description = "Request teammate shutdown") public String shutdownRequest(String teammate) { return tracker.handleShutdownRequest(teammate); }
        @Tool(description = "Check shutdown status") public String shutdownResponse(String requestId) { return tracker.checkShutdownStatus(requestId); }
        @Tool(description = "Approve/reject plan") public String planApproval(String requestId, boolean approve, String feedback) { return tracker.reviewPlan(requestId, approve, feedback); }
        @Tool(description = "Claim a task from the board") public String claimTask(int taskId) { return S11AutonomousAgents.claimTask(tasksDir, taskId, "lead"); }
    }

    public static void main(String[] args) {
        SpringApplication.run(S11AutonomousAgents.class, args);
    }
}
