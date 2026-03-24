package io.mybatis.learn.s12;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件总线 - 追加式JSONL生命周期事件日志
 *
 * TIPS: 对应Python EventBus类（s12第83-118行）。
 * 记录 worktree 和 task 的生命周期事件，用于可观测性。
 * 事件类型：worktree.create.before/after/failed, worktree.remove.*, worktree.keep, task.completed
 */
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Path logPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventBus(Path logPath) {
        this.logPath = logPath;
        try {
            Files.createDirectories(logPath.getParent());
            if (!Files.exists(logPath)) Files.writeString(logPath, "");
            if (log.isDebugEnabled()) {
                System.out.printf("🚀 事件日志就绪: %s%n", logPath);
            }
        } catch (IOException e) {
            log.error("初始化事件日志失败: {}, error={}", logPath, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public synchronized void emit(String event, Map<String, Object> task,
                                  Map<String, Object> worktree, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("ts", System.currentTimeMillis() / 1000.0);
        payload.put("task", task != null ? task : Map.of());
        payload.put("worktree", worktree != null ? worktree : Map.of());
        if (error != null) payload.put("error", error);

        try {
            Files.writeString(logPath, mapper.writeValueAsString(payload) + "\n",
                    StandardOpenOption.APPEND);
            if (log.isDebugEnabled()) {
                System.out.printf("📡 事件已记录: %s%n", event);
            }
        } catch (IOException e) {
            log.warn("事件写入失败: event={}, error={}", event, e.getMessage());
            System.err.println("EventBus emit error: " + e.getMessage());
        }
    }

    public void emit(String event) {
        emit(event, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public String listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        if (log.isDebugEnabled()) {
            System.out.printf("🔍 读取最近 %d 条事件 (请求 %d)%n", n, limit);
        }
        try {
            List<String> lines = Files.readAllLines(logPath);
            List<String> recent = lines.subList(Math.max(0, lines.size() - n), lines.size());
            List<Object> items = new ArrayList<>();
            for (String line : recent) {
                if (!line.isBlank()) {
                    try {
                        items.add(mapper.readValue(line, Map.class));
                    } catch (Exception e) {
                        items.add(Map.of("event", "parse_error", "raw", line));
                    }
                }
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (IOException e) {
            log.warn("读取事件失败: error={}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
