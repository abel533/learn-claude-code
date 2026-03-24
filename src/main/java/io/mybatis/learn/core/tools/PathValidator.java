package io.mybatis.learn.core.tools;

import java.nio.file.Path;

/**
 * 路径安全校验工具。
 * <p>
 * TIP: 对应 Python {@code s02_tool_use.py} 中的 {@code safe_path()} 函数。
 * 防止路径逃逸（path traversal），确保所有文件操作都在工作目录范围内。
 */
public class PathValidator {

    private final Path workDir;

    public PathValidator() {
        this.workDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public PathValidator(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    /**
     * 校验并解析路径，确保不逃逸出工作目录。
     *
     * @param relativePath 相对路径字符串
     * @return 解析后的绝对路径
     * @throws IllegalArgumentException 路径逃逸时抛出
     */
    public Path resolve(String relativePath) {
        Path resolved = workDir.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    public Path getWorkDir() {
        return workDir;
    }
}
