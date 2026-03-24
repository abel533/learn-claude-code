package io.mybatis.learn.s02;

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
 * S02 - 工具使用：扩展 Agent 能触达的范围。
 * <p>
 * 格言: "加一个工具, 只加一个 handler"
 * <p>
 * 核心变化:
 * <pre>
 *   +----------+      +-------+      +------------------+
 *   |   User   | ---> |  LLM  | ---> | Tool Dispatch    |
 *   |  prompt  |      |       |      | {                |
 *   +----------+      +---+---+      |   bash           |
 *                          ^          |   read_file      |
 *                          |          |   write_file     |
 *                          +----------+   edit_file      |
 *                          tool_result| }                |
 *                                     +------------------+
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s02_tool_use.py}。
 * Python 版通过 {@code TOOL_HANDLERS = {"bash": fn, "read_file": fn, ...}} 字典进行分派。
 * Spring AI 通过 {@code @Tool} 注解声明工具，传入 {@code defaultTools()} 即可自动注册和分派。
 * 关键洞察: "循环一点没变，只是多加了工具" —— 在 Spring AI 中更加明显，
 * 因为循环由框架管理，开发者只需关注工具的实现。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S02ToolUse implements CommandLineRunner {

    private final ChatClient chatClient;

    /**
     * TIP: Python 版在模块级定义 4 个工具函数 + TOOL_HANDLERS 字典。
     * Spring AI 只需创建工具对象并传入 defaultTools()，框架自动完成注册和分派。
     */
    public S02ToolUse(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                        + ". Use tools to solve tasks. Act, don't explain.")
                .defaultTools(
                        new BashTool(),
                        new ReadFileTool(),
                        new WriteFileTool(),
                        new EditFileTool()
                )
                .build();
    }

    @Override
    public void run(String... args) {
        /*
         * TIP: 对比 Python 的 agent_loop()，循环代码完全相同——只是 TOOLS 数组多了 3 个工具。
         * 在 Spring AI 中，循环代码也完全相同——只是 defaultTools() 多传了 3 个对象。
         * 这正是 s02 的核心洞察: 扩展能力不需要修改循环。
         */
        AgentRunner.interactive("s02", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()
                        .content()
        );
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S02ToolUse.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
