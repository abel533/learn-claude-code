package io.mybatis.learn.s07;

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
 * S07 - 任务系统：大目标拆成小任务，排好序，记在磁盘上。
 * <p>
 * 格言: "大目标要拆成小任务, 排好序, 记在磁盘上"
 * <p>
 * TIP: 对应 Python {@code agents/s07_task_system.py}。
 * 核心洞察: 任务状态持久化在磁盘（{@code .tasks/task_*.json}），
 * 不受上下文压缩影响 —— 因为它在对话之外。
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S07TaskSystem implements CommandLineRunner {

    private final ChatClient chatClient;

    public S07TaskSystem(ChatModel chatModel) {
        Path tasksDir = Path.of(System.getProperty("user.dir"), ".tasks");
        TaskManager taskManager = new TaskManager(tasksDir);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                        + ". Use task tools to plan and track work.")
                .defaultTools(
                        new BashTool(),
                        new ReadFileTool(),
                        new WriteFileTool(),
                        new EditFileTool(),
                        taskManager
                )
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s07", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()
                        .content()
        );
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S07TaskSystem.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
