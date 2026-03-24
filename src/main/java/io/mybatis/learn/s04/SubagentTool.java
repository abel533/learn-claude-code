package io.mybatis.learn.s04;

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

/**
 * 子 Agent 工具 —— 生成具有独立上下文的子 Agent 执行任务。
 * <p>
 * TIP: 对应 Python {@code agents/s04_subagent.py} 中的 {@code run_subagent(prompt)} 函数。
 * Python 版创建 {@code sub_messages = []} 实现上下文隔离，
 * Spring AI 通过创建全新的 {@link ChatClient} 实例实现相同效果。
 * 子 Agent 获得基础工具但没有 task 工具（防止递归生成）。
 * 只有最终文本返回给父 Agent，子 Agent 的对话历史被完全丢弃。
 */
public class SubagentTool {
    private static final Logger log = LoggerFactory.getLogger(SubagentTool.class);

    private final ChatModel chatModel;
    private final String workDir;

    public SubagentTool(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.workDir = System.getProperty("user.dir");
    }

    /**
     * TIP: 对应 Python {@code run_subagent(prompt)}。
     * Python 版在独立线程中运行子 Agent 的 while 循环（最多30次迭代）。
     * Spring AI 的 ChatClient.call() 内部管理循环，无需手动限制迭代次数。
     */
    @Tool(description = "Spawn a subagent with fresh context. "
            + "It shares the filesystem but not conversation history. "
            + "Use for exploration or subtasks that might pollute the main context.")
    public String task(
            @ToolParam(description = "The task prompt for the subagent") String prompt,
            @ToolParam(description = "Short description of the task", required = false) String description) {

        String desc = (description != null && !description.isBlank()) ? description : "subtask";
        if (log.isDebugEnabled()) {
            System.out.printf("🤖 启动子代理「%s」: %s%n", desc, prompt.substring(0, Math.min(80, prompt.length())));
        }

        // 创建全新的 ChatClient —— 这就是"上下文隔离"的全部
        // TIP: 对应 Python 的 sub_messages = [] —— 空的消息列表就是隔离
        ChatClient subClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding subagent at " + workDir
                        + ". Complete the given task, then summarize your findings.")
                .defaultTools(
                        new BashTool(),
                        new ReadFileTool(),
                        new WriteFileTool(),
                        new EditFileTool()
                )
                .build();

        String result = subClient.prompt()
                .user(prompt)
                .call()
                .content();
        if (log.isDebugEnabled()) {
            System.out.printf("✅ 子代理「%s」完成，返回 %d 字符%n", desc, result == null ? 0 : result.length());
        }

        // 只返回最终文本，子 Agent 上下文被丢弃
        return (result != null && !result.isBlank()) ? result : "(no summary)";
    }
}
