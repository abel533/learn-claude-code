package io.mybatis.learn.core.tools;

import io.mybatis.learn.core.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shell 命令执行工具。
 * <p>
 * TIP: 对应 Python {@code s01_agent_loop.py} 中的 {@code run_bash(command)} 函数。
 * Python 使用 {@code subprocess.run()}，Java 使用 {@code ProcessBuilder}。
 * 安全机制相同：危险命令过滤 + 超时控制 + 输出截断。
 */
public class BashTool {
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 50000;

    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    private final String workDir;

    public BashTool() {
        this.workDir = System.getProperty("user.dir");
    }

    public BashTool(String workDir) {
        this.workDir = workDir;
    }

    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute") String command) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔧 调用工具 [Bash] workDir=%s, command=%s%n", workDir,
                    command.substring(0, Math.min(80, command.length())));
        }
        // 危险命令检查
        for (String pattern : DANGEROUS_PATTERNS) {
            if (command.contains(pattern)) {
                log.warn("拦截危险命令，pattern={}", pattern);
                return "Error: Dangerous command blocked";
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取输出
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("命令执行超时，timeout={}s", TIMEOUT_SECONDS);
                return "Error: Timeout (" + TIMEOUT_SECONDS + "s)";
            }

            output = output.trim();
            if (log.isDebugEnabled()) {
                System.out.printf("✅ 工具完成 [Bash] 输出 %d 字符%n", output.length());
            }
            if (output.isEmpty()) return "(no output)";
            return AgentRunner.truncate(output, MAX_OUTPUT_LENGTH);
        } catch (Exception e) {
            log.warn("命令执行异常，error={}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
