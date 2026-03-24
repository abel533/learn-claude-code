package io.mybatis.learn.core.tools;

import io.mybatis.learn.core.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取工具。
 * <p>
 * TIP: 对应 Python {@code s02_tool_use.py} 中的 {@code run_read(path, limit)} 函数。
 * Python 使用 {@code Path.read_text()}，Java 使用 {@code Files.readString()}。
 * 支持可选的行数限制参数。
 */
public class ReadFileTool {
    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    private static final int MAX_OUTPUT_LENGTH = 50000;

    private final PathValidator pathValidator;

    public ReadFileTool() {
        this.pathValidator = new PathValidator();
    }

    public ReadFileTool(PathValidator pathValidator) {
        this.pathValidator = pathValidator;
    }

    @Tool(description = "Read file contents. Optionally limit the number of lines returned.")
    public String readFile(
            @ToolParam(description = "Relative path to the file") String path,
            @ToolParam(description = "Maximum number of lines to read (0 or negative for all)", required = false) Integer limit) {
        if (log.isDebugEnabled()) {
            System.out.printf("📖 调用工具 [ReadFile] path=%s, limit=%s%n", path, limit);
        }
        try {
            Path filePath = pathValidator.resolve(path);
            List<String> lines = Files.readAllLines(filePath);

            if (limit != null && limit > 0 && limit < lines.size()) {
                lines = lines.subList(0, limit);
                lines.add("... (" + (Files.readAllLines(filePath).size() - limit) + " more lines)");
            }

            String content = String.join("\n", lines);
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 工具完成 [ReadFile] %s 共 %d 行%n", path, lines.size());
            }
            return AgentRunner.truncate(content, MAX_OUTPUT_LENGTH);
        } catch (Exception e) {
            log.warn("读取文件失败: path={}, error={}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
