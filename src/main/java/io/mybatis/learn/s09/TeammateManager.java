package io.mybatis.learn.s09;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.team.MessageBus;
import io.mybatis.learn.core.tools.BashTool;
import io.mybatis.learn.core.tools.EditFileTool;
import io.mybatis.learn.core.tools.ReadFileTool;
import io.mybatis.learn.core.tools.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队管理器 - 持久化命名agent，通过JSONL收件箱通信
 *
 * TIPS: 对应Python TeammateManager类（s09第124-248行）。
 * Python用 threading.Thread 创建队友线程，Java用虚拟线程（Thread.startVirtualThread）。
 * Python队友手动执行 while/tool_use 循环 + _exec分发；
 * Java队友用 ChatClient + @Tool 自动处理工具循环（一次 call() = 完整工具链）。
 * 每次 call() 等价于Python的「循环直到 stop_reason != tool_use」。
 */
public class TeammateManager {
    private static final Logger log = LoggerFactory.getLogger(TeammateManager.class);

    private final ChatModel chatModel;
    private final MessageBus bus;
    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> config;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();

    public TeammateManager(ChatModel chatModel, MessageBus bus, Path teamDir) {
        this.chatModel = chatModel;
        this.bus = bus;
        this.configPath = teamDir.resolve("config.json");
        try {
            Files.createDirectories(teamDir);
        } catch (IOException e) {
            log.error("创建团队目录失败: {}, error={}", teamDir, e.getMessage());
            throw new RuntimeException(e);
        }
        this.config = loadConfig();
        log.info("TeammateManager 初始化完成，configPath={}", configPath);
    }

    // ---- 配置持久化 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        if (Files.exists(configPath)) {
            try {
                if (log.isDebugEnabled()) {
                    System.out.printf("💭 加载团队配置: %s%n", configPath);
                }
                return mapper.readValue(configPath.toFile(), Map.class);
            } catch (IOException e) { /* ignore */ }
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("team_name", "default");
        cfg.put("members", new ArrayList<>());
        return cfg;
    }

    private synchronized void saveConfig() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 团队配置已保存: %s%n", configPath);
            }
        } catch (IOException e) {
            log.warn("保存团队配置失败: error={}", e.getMessage());
            System.err.println("Error saving config: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findMember(String name) {
        List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
        for (Map<String, Object> m : members) {
            if (name.equals(m.get("name"))) return m;
        }
        return null;
    }

    protected synchronized void setStatus(String name, String status) {
        Map<String, Object> member = findMember(name);
        if (member != null) {
            member.put("status", status);
            saveConfig();
        }
    }

    // ---- Spawn ----

    @SuppressWarnings("unchecked")
    public synchronized String spawn(String name, String role, String prompt) {
        log.info("请求启动队友: name={}, role={}", name, role);
        Map<String, Object> member = findMember(name);
        if (member != null) {
            String status = (String) member.get("status");
            if (!"idle".equals(status) && !"shutdown".equals(status)) {
                return "Error: '" + name + "' is currently " + status;
            }
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

        Thread thread = Thread.startVirtualThread(() -> teammateLoop(name, role, prompt));
        threads.put(name, thread);
        log.info("队友已启动: name={}, role={}", name, role);
        return "Spawned '" + name + "' (role: " + role + ")";
    }

    // ---- 队友循环 ----

    /**
     * TIPS: Python队友循环（s09第166-204行）在range(50)内逐次调用LLM。
     * Java用ChatClient.prompt().call()一次完成整个工具链，
     * 等价于Python循环到 stop_reason != "tool_use" 为止。
     * 收件箱检查在每次call()之间进行（而非Python的每次LLM调用之间）。
     */
    protected void teammateLoop(String name, String role, String initialPrompt) {
        log.info("队友工作循环开始: name={}, role={}", name, role);
        String workDir = System.getProperty("user.dir");
        String sysPrompt = String.format(
                "You are '%s', role: %s, at %s. "
                + "Use send_message to communicate. Complete your task.",
                name, role, workDir);

        var messageTool = new TeammateMessageTool(bus, name);
        ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(sysPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(), messageTool)
                .build();

        try {
            // 初始工作
            String response = client.prompt(initialPrompt).call().content();
            System.out.println("  [" + name + "] " + AgentRunner.truncate(response, 120));
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 队友「%s」初始响应完成%n", name);
            }

            // 等待收件箱消息（每2秒检查一次，最多50轮）
            for (int round = 0; round < 50; round++) {
                Thread.sleep(2000);
                var inbox = bus.readInbox(name);
                if (inbox.isEmpty()) break;
                if (log.isDebugEnabled()) {
                    System.out.printf("📨 队友「%s」收到 %d 条收件箱消息%n", name, inbox.size());
                }

                String inboxJson = mapper.writeValueAsString(inbox);
                response = client.prompt("<inbox>" + inboxJson + "</inbox>").call().content();
                System.out.println("  [" + name + "] " + AgentRunner.truncate(response, 120));
            }
        } catch (Exception e) {
            log.warn("队友执行异常: name={}, error={}", name, e.getMessage());
            System.err.println("  [" + name + "] Error: " + e.getMessage());
        }

        setStatus(name, "idle");
        log.info("队友工作循环结束: name={}", name);
    }

    // ---- 查询 ----

    @SuppressWarnings("unchecked")
    public String listAll() {
        List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
        if (members.isEmpty()) return "No teammates.";
        StringBuilder sb = new StringBuilder("Team: " + config.get("team_name"));
        for (Map<String, Object> m : members) {
            sb.append("\n  ").append(m.get("name"))
              .append(" (").append(m.get("role")).append("): ").append(m.get("status"));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> memberNames() {
        List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
        return members.stream().map(m -> (String) m.get("name")).toList();
    }

    // ---- 队友专用消息工具（参数化sender名称） ----

    /**
     * TIPS: Python队友通过 _exec() 分发工具调用（s09第206-220行），
     * send_message和read_inbox使用队友自己的名字作为sender。
     * Java用参数化工具类：构造时绑定sender，@Tool方法自动注入。
     */
    public static class TeammateMessageTool {
        private final MessageBus bus;
        private final String name;
        private final ObjectMapper mapper = new ObjectMapper();

        public TeammateMessageTool(MessageBus bus, String name) {
            this.bus = bus;
            this.name = name;
        }

        @Tool(description = "Send message to a teammate")
        public String sendMessage(
                @ToolParam(description = "Recipient name") String to,
                @ToolParam(description = "Message content") String content) {
            return bus.send(name, to, content);
        }

        @Tool(description = "Read and drain your inbox")
        public String readInbox() {
            try {
                return mapper.writeValueAsString(bus.readInbox(name));
            } catch (Exception e) {
                return "[]";
            }
        }
    }
}
