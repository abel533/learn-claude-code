package io.mybatis.learn.s01;

import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.tools.BashTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * S01 - Agent 循环：AI 编码 Agent 的全部秘密。
 * <p>
 * 格言: "One loop & Bash is all you need"
 * <p>
 * 核心模式:
 * <pre>
 *   +----------+      +-------+      +---------+
 *   |   User   | ---> |  LLM  | ---> |  Tool   |
 *   |  prompt  |      |       |      | execute |
 *   +----------+      +---+---+      +----+----+
 *                          ^               |
 *                          |   tool_result |
 *                          +---------------+
 *                          (loop continues)
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s01_agent_loop.py}。
 * Python 版本手动实现 while 循环 + tool dispatch。
 * Spring AI 的 {@link ChatClient} 内置了工具调用循环，
 * 调用 {@code .tools(...).call()} 时自动完成：发送请求 → 检测 tool_use → 执行工具 → 回传结果 → 重复。
 * 因此 Java 版本无需手写 while 循环，一行 {@code chatClient.prompt()...call()} 即可。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S01AgentLoop implements CommandLineRunner {

    private final ChatClient chatClient;

    /**
     * TIP: Python 版在模块级创建 client = Anthropic() 和 MODEL。
     * Spring AI 通过自动配置注入 ChatModel，再用 builder 构建 ChatClient。
     */
    public S01AgentLoop(ChatModel chatModel) {
        // TIP: Python 的 SYSTEM prompt 对应 ChatClient 的 defaultSystem
        // TIP: Python 的 TOOLS 数组 + TOOL_HANDLERS 对应 Spring AI 的 @Tool 注解 + defaultTools
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                        + ". Use bash to solve tasks. Act, don't explain.")
                .defaultTools(new BashTool())
                .build();
    }

    @Override
    public void run(String... args) {
        /*
         * TIP: 对应 Python 的 agent_loop(messages) 函数。
         *
         * Python 版需要手动实现:
         *   while True:
         *       response = client.messages.create(model, messages, tools)
         *       if response.stop_reason != "tool_use": return
         *       执行工具, 收集结果
         *       messages.append(tool_results)
         *
         * Spring AI 的 ChatClient.call() 内部已封装此循环，
         * 包括工具调用检测、自动执行、结果回传，直到模型返回最终文本。
         */
        AgentRunner.interactive("s01", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()
                        .content()
        );
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S01AgentLoop.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
