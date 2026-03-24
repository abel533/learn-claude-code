package io.mybatis.learn.s06;

import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.tools.BashTool;
import io.mybatis.learn.core.tools.EditFileTool;
import io.mybatis.learn.core.tools.ReadFileTool;
import io.mybatis.learn.core.tools.WriteFileTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * S06 - 上下文压缩：三层压缩管线，让 Agent 永远不会"忘记"。
 * <p>
 * 格言: "上下文总会满, 要有办法腾地方"
 * <p>
 * 三层压缩:
 * <pre>
 *   [Layer 1: micro_compact]    每轮调用前，替换旧工具结果为占位符
 *        |
 *   [Check: tokens > 50000?]
 *      |           |
 *      no          yes
 *      |           |
 *   continue  [Layer 2: auto_compact]
 *               保存 transcript → LLM 生成摘要 → 替换 messages
 *                    |
 *              [Layer 3: compact tool]
 *               Agent 主动调用 → 立即压缩
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s06_context_compact.py}。
 * Python 版在工具循环内（每次 LLM 调用前）执行 micro_compact 和 auto_compact。
 * Spring AI 的 ChatClient 自动管理工具循环，因此压缩在用户消息级别触发。
 * 核心教学概念不变: Agent 可以策略性地"遗忘"，然后继续无限工作。
 * 如需循环内精细压缩，可实现自定义 {@code CallAroundAdvisor}。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S06ContextCompact implements CommandLineRunner {

    private final ChatModel chatModel;

    public S06ContextCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        ContextCompactor compactor = new ContextCompactor(chatModel, workDir);
        CompactTool compactTool = new CompactTool(compactor);

        String baseSystem = "You are a coding agent at " + workDir + ". Use tools to solve tasks.";

        AgentRunner.interactive("s06", userMessage -> {
            // Layer 2: 自动压缩检查（每次用户输入前）
            if (compactor.needsAutoCompact()) {
                System.out.println("[auto_compact triggered - " + compactor.estimateTokens() + " tokens]");
                compactor.compact();
            }

            // 记录用户输入
            compactor.addTurn("user", userMessage);

            // 动态系统提示：包含对话上下文摘要
            String system = baseSystem + compactor.getContextSummary();

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(system)
                    .defaultTools(
                            new BashTool(),
                            new ReadFileTool(),
                            new WriteFileTool(),
                            new EditFileTool(),
                            compactTool
                    )
                    .build();

            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            // 记录 Agent 回复
            compactor.addTurn("assistant", response != null ? response : "");

            // Layer 3: 手动压缩（如果 Agent 调用了 compact 工具）
            if (compactor.isCompactRequested()) {
                System.out.println("[manual compact triggered]");
                String summary = compactor.compact();
                System.out.println("[compressed to summary: " + summary.length() + " chars]");
            }

            return response;
        });
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S06ContextCompact.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
