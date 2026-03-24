package io.mybatis.learn.s06;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 手动压缩工具（Layer 3）。
 * <p>
 * TIP: 对应 Python {@code TOOL_HANDLERS["compact"]}。
 * 当 Agent 调用此工具时，触发上下文压缩。
 */
public class CompactTool {

    private final ContextCompactor compactor;

    public CompactTool(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    @Tool(description = "Trigger manual conversation compression to free up context space. "
            + "Use when the conversation is getting long or you want to start fresh with a summary.")
    public String compact(
            @ToolParam(description = "What to focus on preserving in the summary", required = false) String focus) {
        compactor.requestCompact();
        return "Compression triggered. Context will be summarized.";
    }
}
