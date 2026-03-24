package io.mybatis.learn.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件写入工具。
 * <p>
 * TIP: 对应 Python {@code s02_tool_use.py} 中的 {@code run_write(path, content)} 函数。
 * Python 使用 {@code Path.write_text()}，Java 使用 {@code Files.writeString()}。
 * 自动创建父目录。
 */
public class WriteFileTool {
    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);

    private final PathValidator pathValidator;

    public WriteFileTool() {
        this.pathValidator = new PathValidator();
    }

    public WriteFileTool(PathValidator pathValidator) {
        this.pathValidator = pathValidator;
    }

    @Tool(description = "Write content to a file. Creates parent directories if needed.")
    public String writeFile(
            @ToolParam(description = "Relative path to the file") String path,
            @ToolParam(description = "Content to write") String content) {
        if (log.isDebugEnabled()) {
            System.out.printf("✏️ 调用工具 [WriteFile] path=%s (%d 字符)%n", path, content == null ? 0 : content.length());
        }
        try {
            Path filePath = pathValidator.resolve(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 工具完成 [WriteFile] %s (%d 字节)%n", path, content.length());
            }
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) {
            log.warn("写文件失败: path={}, error={}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
