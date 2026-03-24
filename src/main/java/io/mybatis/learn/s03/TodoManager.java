package io.mybatis.learn.s03;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo 任务管理器 —— Agent 追踪自身进度的结构化状态。
 * <p>
 * TIP: 对应 Python {@code agents/s03_todo_write.py} 中的 {@code TodoManager} 类。
 * Python 版通过 {@code TOOL_HANDLERS["todo"]} 注册，
 * Spring AI 使用 {@code @Tool} 注解直接在方法上声明。
 * <p>
 * 验证规则:
 * <ul>
 *   <li>最多 20 个 todo</li>
 *   <li>最多 1 个 in_progress 状态</li>
 *   <li>text 不能为空</li>
 *   <li>status 只能是 pending / in_progress / completed</li>
 * </ul>
 */
public class TodoManager {

    /**
     * Todo 项数据结构。
     * TIP: 对应 Python 中 todo item 字典 {@code {"id": ..., "text": ..., "status": ...}}。
     */
    public record TodoItem(String id, String text, String status) {
    }

    private static final List<String> VALID_STATUSES = List.of("pending", "in_progress", "completed");

    private List<TodoItem> items = new ArrayList<>();

    /**
     * 更新整个 todo 列表。
     * TIP: 对应 Python {@code TodoManager.update(items)}，验证逻辑完全一致。
     */
    @Tool(description = "Update the full task list to track progress on multi-step tasks. "
            + "Each item must have id, text, and status (pending/in_progress/completed). "
            + "Only one task can be in_progress at a time. Max 20 items.")
    public String updateTodos(
            @ToolParam(description = "The complete list of todo items") List<TodoItem> items) {
        if (items.size() > 20) {
            return "Error: Max 20 todos allowed";
        }

        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            String id = (item.id() != null && !item.id().isBlank()) ? item.id() : String.valueOf(i + 1);
            String text = item.text();
            String status = (item.status() != null) ? item.status().toLowerCase() : "pending";

            if (text == null || text.isBlank()) {
                return "Error: Item " + id + ": text required";
            }
            if (!VALID_STATUSES.contains(status)) {
                return "Error: Item " + id + ": invalid status '" + status + "'";
            }
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }
            validated.add(new TodoItem(id, text.trim(), status));
        }

        if (inProgressCount > 1) {
            return "Error: Only one task can be in_progress at a time";
        }

        this.items = validated;
        return render();
    }

    /**
     * 渲染当前 todo 列表为可读文本。
     * TIP: 对应 Python {@code TodoManager.render()}，输出格式完全一致。
     */
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }

        StringBuilder sb = new StringBuilder();
        long done = 0;
        for (TodoItem item : items) {
            String marker = switch (item.status()) {
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[ ]";
            };
            sb.append(marker).append(" #").append(item.id()).append(": ").append(item.text()).append("\n");
            if ("completed".equals(item.status())) done++;
        }
        sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
        return sb.toString();
    }
}
