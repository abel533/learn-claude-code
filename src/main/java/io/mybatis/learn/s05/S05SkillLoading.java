package io.mybatis.learn.s05;

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
 * S05 - 技能加载：用到什么知识，临时加载什么知识。
 * <p>
 * 格言: "用到什么知识, 临时加载什么知识"
 * <p>
 * 两层注入:
 * <pre>
 *   System prompt:
 *   +--------------------------------------+
 *   | You are a coding agent.              |
 *   | Skills available:                    |
 *   |   - pdf: Process PDF files...        |  ← Layer 1: 仅元数据 (~100 tokens)
 *   |   - code-review: Review code...      |
 *   +--------------------------------------+
 *
 *   When model calls loadSkill("pdf"):
 *   +--------------------------------------+
 *   | tool_result:                         |
 *   | &lt;skill&gt;                              |
 *   |   Full PDF processing instructions   |  ← Layer 2: 完整内容
 *   |   Step 1: ...                        |
 *   | &lt;/skill&gt;                             |
 *   +--------------------------------------+
 * </pre>
 * <p>
 * TIP: 对应 Python {@code agents/s05_skill_loading.py}。
 * 核心洞察: "不要把所有东西塞进系统提示。按需加载。"
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S05SkillLoading implements CommandLineRunner {

    private final ChatClient chatClient;

    public S05SkillLoading(ChatModel chatModel) {
        Path skillsDir = Path.of(System.getProperty("user.dir"), "skills");
        SkillLoader skillLoader = new SkillLoader(skillsDir);

        // Layer 1: 技能元数据注入系统提示
        String system = "You are a coding agent at " + System.getProperty("user.dir") + ".\n"
                + "Use loadSkill to access specialized knowledge before tackling unfamiliar topics.\n\n"
                + "Skills available:\n"
                + skillLoader.getDescriptions();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(system)
                .defaultTools(
                        new BashTool(),
                        new ReadFileTool(),
                        new WriteFileTool(),
                        new EditFileTool(),
                        skillLoader  // Layer 2: loadSkill @Tool 方法
                )
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s05", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()
                        .content()
        );
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(S05SkillLoading.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
