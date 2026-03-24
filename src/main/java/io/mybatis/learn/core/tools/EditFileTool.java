package io.mybatis.learn.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件编辑工具（查找替换）。
 * <p>
 * TIP: 对应 Python {@code s02_tool_use.py} 中的 {@code run_edit(path, old_text, new_text)} 函数。
 * Python 使用 {@code str.replace(old, new, 1)}，Java 使用 {@code String.replaceFirst()}。
 * 只替换第一次出现的匹配文本。
 */
public class EditFileTool {
    private static final Logger log = LoggerFactory.getLogger(EditFileTool.class);

    private final PathValidator pathValidator;

    public EditFileTool() {
        this.pathValidator = new PathValidator();
    }

    public EditFileTool(PathValidator pathValidator) {
        this.pathValidator = pathValidator;
    }

    @Tool(description = "Replace exact text in a file. Only the first occurrence is replaced.")
    public String editFile(
            @ToolParam(description = "Relative path to the file") String path,
            @ToolParam(description = "The exact text to find") String oldText,
            @ToolParam(description = "The replacement text") String newText) {
        if (log.isDebugEnabled()) {
            System.out.printf("✏️ 调用工具 [EditFile] path=%s (替换 %d→%d 字符)%n",
                    path, oldText == null ? 0 : oldText.length(), newText == null ? 0 : newText.length());
        }
        try {
            Path filePath = pathValidator.resolve(path);
            String content = Files.readString(filePath);

            if (!content.contains(oldText)) {
                log.warn("编辑文件失败，未找到目标文本: path={}", path);
                return "Error: Text not found in " + path;
            }

            // 只替换第一次出现（使用 indexOf + substring 避免正则转义问题）
            int index = content.indexOf(oldText);
            String updated = content.substring(0, index) + newText + content.substring(index + oldText.length());
            Files.writeString(filePath, updated);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 工具完成 [EditFile] %s%n", path);
            }
            return "Edited " + path;
        } catch (Exception e) {
            log.warn("编辑文件异常: path={}, error={}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
