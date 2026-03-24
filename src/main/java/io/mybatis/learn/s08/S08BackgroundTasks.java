package io.mybatis.learn.s08;

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

import java.util.stream.Collectors;

/**
 * S08 - 后台任务：慢操作丢后台，Agent 继续想下一步。
 * <p>
 * 格言: "慢操作丢后台, agent 继续想下一步"
 * <p>
 * TIP: 对应 Python {@code agents/s08_background_tasks.py}。
 * Python 在每次 LLM 调用前 drain 通知队列并注入 {@code <background-results>}。
 * Spring AI 的 ChatClient 管理内部循环，因此改为在每次用户输入时
 * drain 通知并注入系统提示。核心概念不变: fire and forget。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S08BackgroundTasks implements CommandLineRunner {

    private final ChatModel chatModel;

    public S08BackgroundTasks(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {
        BackgroundManager bgManager = new BackgroundManager();
        String workDir = System.getProperty("user.dir");

        AgentRunner.interactive("s08", userMessage -> {
            // Drain 后台任务通知（对应 Python 中循环前的 drain_notifications）
            var notifs = bgManager.drainNotifications();
            String bgContext = "";
            if (!notifs.isEmpty()) {
                String notifText = notifs.stream()
                        .map(n -> "[bg:" + n.taskId() + "] " + n.status() + ": " + n.result())
                        .collect(Collectors.joining("\n"));
                bgContext = "\n\n<background-results>\n" + notifText + "\n</background-results>";
                System.out.println("[Background tasks completed: " + notifs.size() + "]");
            }

            String system = "You are a coding agent at " + workDir
                    + ". Use backgroundRun for long-running commands." + bgContext;

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(system)
                    .defaultTools(
                            new BashTool(),
                            new ReadFileTool(),
                            new WriteFileTool(),
                            new EditFileTool(),
                            bgManager
                    )
                    .build();

            return chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
        });
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S08BackgroundTasks.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
