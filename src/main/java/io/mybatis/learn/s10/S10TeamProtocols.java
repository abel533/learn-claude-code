package io.mybatis.learn.s10;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.team.MessageBus;
import io.mybatis.learn.core.tools.*;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * S10 - Team Protocols：关闭协议 + 计划审批协议
 *
 * TIPS: 对应Python s10_team_protocols.py。
 * 在S09基础上新增3个Lead工具和2个Teammate工具。
 *
 * 关闭协议FSM: pending → approved | rejected
 *   Lead调用 shutdown_request → Teammate收到 shutdown_request 消息
 *   → Teammate调用 shutdown_response → Lead收件箱收到响应
 *
 * 计划审批FSM: pending → approved | rejected
 *   Teammate调用 plan_approval → Lead收件箱收到计划
 *   → Lead调用 plan_approval(review) → Teammate收到审批结果
 *
 * 关键洞察："Same request_id correlation pattern, two domains."
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S10TeamProtocols implements CommandLineRunner {

    @Autowired
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        Path workDir = Path.of(System.getProperty("user.dir"));
        Path teamDir = workDir.resolve(".team");

        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        ProtocolTracker tracker = new ProtocolTracker(bus);
        S10TeammateManager team = new S10TeammateManager(chatModel, bus, tracker, teamDir);
        S10LeadTools leadTools = new S10LeadTools(bus, team, tracker);
        ObjectMapper mapper = new ObjectMapper();

        String systemPrompt = "You are a team lead at " + workDir
                + ". Manage teammates with shutdown and plan approval protocols.";

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(), leadTools)
                .build();

        AgentRunner.interactive("s10", input -> {
            if ("/team".equals(input)) return team.listAll();
            if ("/inbox".equals(input)) {
                try { return mapper.writeValueAsString(bus.readInbox("lead")); }
                catch (Exception e) { return "Error: " + e.getMessage(); }
            }

            var inbox = bus.readInbox("lead");
            String fullInput = input;
            if (!inbox.isEmpty()) {
                try {
                    fullInput = "<inbox>" + mapper.writeValueAsString(inbox)
                            + "</inbox>\n\n" + input;
                } catch (Exception e) { /* ignore */ }
            }

            return chatClient.prompt(fullInput).call().content();
        });
    }

    // ---- 队友管理器（增加协议处理） ----

    /**
     * TIPS: 对应Python s10第134-290行的TeammateManager。
     * 相比S09增加了：
     *   1. should_exit标志 - 队友批准关闭后退出循环
     *   2. shutdown_response工具 - 更新tracker + 发送响应消息
     *   3. plan_approval工具 - 提交计划到lead收件箱
     */
    static class S10TeammateManager {
        private final ChatModel chatModel;
        private final MessageBus bus;
        private final ProtocolTracker tracker;
        private final Path configPath;
        private final ObjectMapper mapper = new ObjectMapper();
        private Map<String, Object> config;

        S10TeammateManager(ChatModel chatModel, MessageBus bus,
                           ProtocolTracker tracker, Path teamDir) {
            this.chatModel = chatModel;
            this.bus = bus;
            this.tracker = tracker;
            this.configPath = teamDir.resolve("config.json");
            try { Files.createDirectories(teamDir); } catch (IOException e) { throw new RuntimeException(e); }
            this.config = loadConfig();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> loadConfig() {
            if (Files.exists(configPath)) {
                try { return mapper.readValue(configPath.toFile(), Map.class); } catch (IOException e) { /* ignore */ }
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
            Thread.startVirtualThread(() -> teammateLoop(name, role, prompt));
            return "Spawned '" + name + "' (role: " + role + ")";
        }

        private void teammateLoop(String name, String role, String initialPrompt) {
            String workDir = System.getProperty("user.dir");
            String sysPrompt = String.format(
                    "You are '%s', role: %s, at %s. "
                    + "Submit plans via plan_approval before major work. "
                    + "Respond to shutdown_request with shutdown_response.",
                    name, role, workDir);

            // 队友协议工具
            var protocolTool = new TeammateProtocolTool(bus, tracker, name);
            var messageTool = new io.mybatis.learn.s09.TeammateManager.TeammateMessageTool(bus, name);

            ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem(sysPrompt)
                    .defaultTools(new BashTool(), new ReadFileTool(),
                            new WriteFileTool(), new EditFileTool(),
                            messageTool, protocolTool)
                    .build();

            try {
                client.prompt(initialPrompt).call().content();

                for (int round = 0; round < 50; round++) {
                    Thread.sleep(2000);
                    var inbox = bus.readInbox(name);
                    if (inbox.isEmpty()) break;

                    // 检查是否有关闭请求
                    boolean hasShutdown = inbox.stream()
                            .anyMatch(m -> "shutdown_request".equals(m.get("type")));

                    String inboxJson = mapper.writeValueAsString(inbox);
                    client.prompt("<inbox>" + inboxJson + "</inbox>").call().content();

                    if (hasShutdown) {
                        setStatus(name, "shutdown");
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("  [" + name + "] Error: " + e.getMessage());
            }

            setStatus(name, "idle");
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

    // ---- 队友协议工具 ----

    public static class TeammateProtocolTool {
        private final MessageBus bus;
        private final ProtocolTracker tracker;
        private final String name;

        public TeammateProtocolTool(MessageBus bus, ProtocolTracker tracker, String name) {
            this.bus = bus;
            this.tracker = tracker;
            this.name = name;
        }

        @Tool(description = "Respond to a shutdown request. Approve to shut down, reject to keep working.")
        public String shutdownResponse(
                @ToolParam(description = "The request_id from shutdown request") String requestId,
                @ToolParam(description = "true to approve shutdown") boolean approve,
                @ToolParam(description = "Reason for decision") String reason) {
            return tracker.respondToShutdown(name, requestId, approve, reason);
        }

        @Tool(description = "Submit a plan for lead approval before major work")
        public String planApproval(
                @ToolParam(description = "Plan text to submit") String plan) {
            return tracker.submitPlan(name, plan);
        }
    }

    // ---- Lead工具集（12个工具 = S09的9个 + 3个协议工具） ----

    public static class S10LeadTools {
        private final MessageBus bus;
        private final S10TeammateManager team;
        private final ProtocolTracker tracker;
        private final ObjectMapper mapper = new ObjectMapper();

        public S10LeadTools(MessageBus bus, S10TeammateManager team, ProtocolTracker tracker) {
            this.bus = bus;
            this.team = team;
            this.tracker = tracker;
        }

        @Tool(description = "Spawn a persistent teammate")
        public String spawnTeammate(String name, String role, String prompt) {
            return team.spawn(name, role, prompt);
        }

        @Tool(description = "List all teammates")
        public String listTeammates() { return team.listAll(); }

        @Tool(description = "Send a message to a teammate's inbox")
        public String sendMessage(String to, String content) {
            return bus.send("lead", to, content);
        }

        @Tool(description = "Read and drain the lead's inbox")
        public String readInbox() {
            try { return mapper.writeValueAsString(bus.readInbox("lead")); }
            catch (Exception e) { return "[]"; }
        }

        @Tool(description = "Broadcast message to all teammates")
        public String broadcast(String content) {
            return bus.broadcast("lead", content, team.memberNames());
        }

        @Tool(description = "Request a teammate to shut down gracefully. Returns request_id for tracking.")
        public String shutdownRequest(
                @ToolParam(description = "Teammate name to shut down") String teammate) {
            return tracker.handleShutdownRequest(teammate);
        }

        @Tool(description = "Check shutdown request status by request_id")
        public String shutdownResponse(
                @ToolParam(description = "The request_id to check") String requestId) {
            return tracker.checkShutdownStatus(requestId);
        }

        @Tool(description = "Approve or reject a teammate's plan")
        public String planApproval(
                @ToolParam(description = "Plan request_id") String requestId,
                @ToolParam(description = "true to approve") boolean approve,
                @ToolParam(description = "Feedback text") String feedback) {
            return tracker.reviewPlan(requestId, approve, feedback);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(S10TeamProtocols.class, args);
    }
}
