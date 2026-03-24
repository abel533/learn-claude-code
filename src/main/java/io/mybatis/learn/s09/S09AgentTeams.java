package io.mybatis.learn.s09;

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

import java.nio.file.Path;
import java.util.List;

/**
 * S09 - Agent Teams：持久化命名agent + JSONL收件箱通信
 *
 * TIPS: 对应Python s09_agent_teams.py。
 * Python用 TOOL_HANDLERS 字典 + 手动while循环分发9个工具；
 * Java用 ChatClient + @Tool 自动处理，Lead工具封装在 LeadTools 内部类。
 *
 * 核心概念：
 *   Subagent (s04): spawn → execute → return summary → destroyed
 *   Teammate (s09): spawn → work → idle → work → ... → shutdown
 *
 * 关键差异：Python队友在每次LLM调用前检查收件箱（s09第174行），
 * Java队友在每次完整工具链（call()）之间检查。这是Spring AI的自然适配。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S09AgentTeams implements CommandLineRunner {

    @Autowired
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        Path workDir = Path.of(System.getProperty("user.dir"));
        Path teamDir = workDir.resolve(".team");

        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        TeammateManager team = new TeammateManager(chatModel, bus, teamDir);
        LeadTools leadTools = new LeadTools(bus, team);
        ObjectMapper mapper = new ObjectMapper();

        String systemPrompt = "You are a team lead at " + workDir
                + ". Spawn teammates and communicate via inboxes.";

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(), leadTools)
                .build();

        AgentRunner.interactive("s09", input -> {
            if ("/team".equals(input)) return team.listAll();
            if ("/inbox".equals(input)) {
                try {
                    return mapper.writeValueAsString(bus.readInbox("lead"));
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }

            // 每次LLM调用前检查lead收件箱
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

    /**
     * Lead专用工具集（5个团队管理工具）
     *
     * TIPS: 对应Python TOOL_HANDLERS中的5个lead工具（s09第310-319行）。
     * Python用lambda + 字典映射；Java用@Tool注解的方法。
     */
    public static class LeadTools {
        private final MessageBus bus;
        private final TeammateManager team;
        private final ObjectMapper mapper = new ObjectMapper();

        public LeadTools(MessageBus bus, TeammateManager team) {
            this.bus = bus;
            this.team = team;
        }

        @Tool(description = "Spawn a persistent teammate that runs in its own thread")
        public String spawnTeammate(
                @ToolParam(description = "Teammate name") String name,
                @ToolParam(description = "Role description") String role,
                @ToolParam(description = "Initial task prompt") String prompt) {
            return team.spawn(name, role, prompt);
        }

        @Tool(description = "List all teammates with name, role, status")
        public String listTeammates() {
            return team.listAll();
        }

        @Tool(description = "Send a message to a teammate's inbox")
        public String sendMessage(
                @ToolParam(description = "Recipient teammate name") String to,
                @ToolParam(description = "Message content") String content) {
            return bus.send("lead", to, content);
        }

        @Tool(description = "Read and drain the lead's inbox")
        public String readInbox() {
            try {
                return mapper.writeValueAsString(bus.readInbox("lead"));
            } catch (Exception e) {
                return "[]";
            }
        }

        @Tool(description = "Send a message to all teammates")
        public String broadcast(
                @ToolParam(description = "Message content") String content) {
            return bus.broadcast("lead", content, team.memberNames());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(S09AgentTeams.class, args);
    }
}
