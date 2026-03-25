package io.mybatis.learn.s03;

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
 * S03 - TodoWrite：让 Agent 追踪自己的进度。
 * <p>
 * 格言: "没有计划的 agent 走哪算哪"
 * <p>
 * 核心模式:
 * <pre>
 *   +----------+      +-------+      +---------+
 *   |   User   | ---> |  LLM  | ---> | Tools   |
 *   |  prompt  |      |       |      | + todo  |
 *   +----------+      +---+---+      +----+----+
 *                          ^               |
 *                          |   tool_result |
 *                          +---------------+
 *                                |
 *                    +-----------+-----------+
 *                    | TodoManager state     |
 *                    | [ ] task A            |
 *                    | [>] task B            |
 *                    | [x] task C            |
 *                    +-----------------------+
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s03_todo_write.py}。
 * Python 版在工具循环内追踪 {@code rounds_since_todo}，
 * 连续 3 轮未调用 todo 时注入 {@code <reminder>Update your todos.</reminder>} 文本。
 * Spring AI 的 ChatClient 自动管理工具循环，无法在循环内注入文本，
 * 因此改用系统提示注入当前 todo 状态 + 强调更新指令的方式实现同等效果。
 * 如需精确的轮次追踪，可实现自定义 {@code Advisor}。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S03TodoWrite implements CommandLineRunner {

    private final ChatModel chatModel;
    private final TodoManager todoManager = new TodoManager();

    public S03TodoWrite(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {
        String workDir = System.getProperty("user.dir");

        /*
         * TIP: 每次用户输入时重建 ChatClient，将最新的 todo 状态注入系统提示。
         * 这对应 Python 版在 agent_loop() 中每轮都能看到 TodoManager 状态的效果。
         */
        AgentRunner.interactive("s03", userMessage -> {
            // 动态系统提示：包含当前 todo 状态
            String system = "You are a coding agent at " + workDir + ".\n"
                    + "Use the todo tool to plan multi-step tasks. "
                    + "Mark in_progress before starting, completed when done.\n"
                    + "Prefer tools over prose.\n"
                    + "IMPORTANT: You MUST call updateTodos regularly to track your progress.\n\n"
                    + "<current-todos>\n" + todoManager.render() + "\n</current-todos>";

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(system)
                    .defaultTools(
                            new BashTool(),
                            new ReadFileTool(),
                            new WriteFileTool(),
                            new EditFileTool(),
                            todoManager
                    )
                    .build();

            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            return response;
        });
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S03TodoWrite.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
