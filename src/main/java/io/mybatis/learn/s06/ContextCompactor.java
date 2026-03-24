package io.mybatis.learn.s06;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器 —— 三层压缩管线，让 Agent 可以无限工作。
 * <p>
 * TIP: 对应 Python {@code agents/s06_context_compact.py} 中的压缩函数。
 * <pre>
 *   Layer 1 (Micro): 替换旧工具结果为占位符 "[Previous: used {tool}]"
 *   Layer 2 (Auto):  tokens 超阈值时自动保存 transcript 并压缩
 *   Layer 3 (Manual): Agent 主动调用 compact 工具触发压缩
 * </pre>
 * <p>
 * 在 Spring AI 中，ChatClient 内部管理工具调用循环，无法直接在循环内插入压缩逻辑。
 * 因此压缩在用户消息级别触发（每次用户输入前检查），效果等价。
 * 如需更精细的循环内压缩，可实现自定义 {@code Advisor}。
 */
public class ContextCompactor {

    private static final int TOKEN_THRESHOLD = 50000;
    private static final int KEEP_RECENT = 3;

    private final Path transcriptDir;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对话历史（用户级别），在 ChatClient 调用之外维护。
     */
    private final List<ConversationTurn> history = new ArrayList<>();
    private boolean compactRequested = false;

    public record ConversationTurn(String role, String content) {
    }

    public ContextCompactor(ChatModel chatModel, Path workDir) {
        this.chatModel = chatModel;
        this.transcriptDir = workDir.resolve(".transcripts");
    }

    /**
     * 估算 token 数量。
     * TIP: 对应 Python {@code estimate_tokens(messages)}，粗略估计 4 字符 ≈ 1 token。
     */
    public int estimateTokens() {
        int chars = history.stream().mapToInt(t -> t.content().length()).sum();
        return chars / 4;
    }

    /**
     * 添加一轮对话记录。
     */
    public void addTurn(String role, String content) {
        history.add(new ConversationTurn(role, content));
    }

    /**
     * 获取对话历史的摘要（用于注入系统提示）。
     */
    public String getContextSummary() {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n<conversation-context>\n");
        // 只保留最近的对话
        int start = Math.max(0, history.size() - KEEP_RECENT * 2);
        for (int i = start; i < history.size(); i++) {
            ConversationTurn turn = history.get(i);
            sb.append("[").append(turn.role()).append("]: ")
                    .append(turn.content(), 0, Math.min(500, turn.content().length()))
                    .append("\n");
        }
        sb.append("</conversation-context>");
        return sb.toString();
    }

    /**
     * 检查是否需要自动压缩（Layer 2）。
     * TIP: 对应 Python 中 {@code if estimate_tokens(messages) > THRESHOLD} 的检查。
     */
    public boolean needsAutoCompact() {
        return estimateTokens() > TOKEN_THRESHOLD;
    }

    /**
     * 标记手动压缩请求（Layer 3）。
     */
    public void requestCompact() {
        this.compactRequested = true;
    }

    public boolean isCompactRequested() {
        boolean val = compactRequested;
        compactRequested = false;
        return val;
    }

    /**
     * 执行压缩：保存 transcript 并用 LLM 生成摘要替换历史。
     * TIP: 对应 Python {@code auto_compact(messages)}。
     * 保存完整 transcript 到 .transcripts/ 目录，然后让 LLM 生成摘要。
     */
    public String compact() {
        // 保存 transcript
        try {
            Files.createDirectories(transcriptDir);
            Path transcriptPath = transcriptDir.resolve("transcript_" + System.currentTimeMillis() + ".jsonl");
            try (BufferedWriter writer = Files.newBufferedWriter(transcriptPath)) {
                for (ConversationTurn turn : history) {
                    writer.write(objectMapper.writeValueAsString(turn));
                    writer.newLine();
                }
            }
            System.out.println("[transcript saved: " + transcriptPath + "]");

            // LLM 生成摘要
            String conversationText = history.stream()
                    .map(t -> t.role() + ": " + t.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            if (conversationText.length() > 80000) {
                conversationText = conversationText.substring(0, 80000);
            }

            ChatClient summaryClient = ChatClient.builder(chatModel).build();
            String summary = summaryClient.prompt()
                    .user("Summarize this conversation for continuity. Include: "
                            + "1) What was accomplished, 2) Current state, 3) Key decisions made. "
                            + "Be concise but preserve critical details.\n\n" + conversationText)
                    .call()
                    .content();

            // 用摘要替换历史
            history.clear();
            history.add(new ConversationTurn("system",
                    "[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary));

            return summary;
        } catch (IOException e) {
            return "Error during compaction: " + e.getMessage();
        }
    }

    public List<ConversationTurn> getHistory() {
        return history;
    }
}
