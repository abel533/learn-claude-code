package io.mybatis.learn.s04;

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

/**
 * S04 - 子 Agent：上下文隔离，保护主 Agent 的思维清晰。
 * <p>
 * 格言: "大任务拆小, 每个小任务干净的上下文"
 * <p>
 * 核心模式:
 * <pre>
 *   Parent agent                    Subagent
 *   +------------------+            +------------------+
 *   | messages=[...]   |            | messages=[]      |  ← fresh
 *   |                  |  dispatch  |                  |
 *   | tool: task       | --------> | ChatClient.call  |
 *   |   prompt="..."   |           |   execute tools   |
 *   |                  |  summary  |                  |
 *   |   result = "..." | <-------- | return last text |
 *   +------------------+            +------------------+
 *           |
 *   Parent context stays clean.
 *   Subagent context is discarded.
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s04_subagent.py}。
 * Python 版手动创建空的 messages 列表实现隔离。
 * Spring AI 通过创建独立的 {@link ChatClient} 实例实现相同效果 —— 更加自然。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S04Subagent implements CommandLineRunner {

    private final ChatClient chatClient;

    public S04Subagent(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                        + ". Use the task tool to delegate exploration or subtasks.")
                .defaultTools(
                        new BashTool(),
                        new ReadFileTool(),
                        new WriteFileTool(),
                        new EditFileTool(),
                        new SubagentTool(chatModel)  // 父 Agent 独有的 task 工具
                )
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s04", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()
                        .content()
        );
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S04Subagent.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
