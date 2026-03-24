# s03: TodoWrite (待办写入)

`s01 > s02 > [ s03 ] s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"没有计划的 agent 走哪算哪"* -- 先列步骤再动手, 完成率翻倍。
>
> **Harness 层**: 规划 -- 让模型不偏航, 但不替它画航线。

## 问题

多步任务中, 模型会丢失进度 -- 重复做过的事、跳步、跑偏。对话越长越严重: 工具结果不断填满上下文, 系统提示的影响力逐渐被稀释。一个 10 步重构可能做完 1-3 步就开始即兴发挥, 因为 4-10 步已经被挤出注意力了。

## 解决方案

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> | Tools   |
| prompt |      |       |      | + todo  |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                          |
              +-----------+-----------+
              | TodoManager state     |
              | [ ] task A            |
              | [>] task B  <- doing  |
              | [x] task C            |
              +-----------------------+
                          |
              每次请求时通过 defaultSystem()
                注入最新 todo 状态到系统提示
```

## 工作原理

1. TodoManager 存储带状态的项目。同一时间只允许一个 `in_progress`。

```java
public class TodoManager {

    public record TodoItem(String id, String text, String status) {}

    private List<TodoItem> items = new ArrayList<>();

    @Tool(description = "Update the full task list to track progress. "
            + "Each item must have id, text, status (pending/in_progress/completed). "
            + "Only one task can be in_progress at a time. Max 20 items.")
    public String updateTodos(
            @ToolParam(description = "The complete list of todo items")
            List<TodoItem> items) {
        if (items.size() > 20) return "Error: Max 20 todos allowed";
        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;
        for (TodoItem item : items) {
            String status = (item.status() != null)
                    ? item.status().toLowerCase() : "pending";
            if ("in_progress".equals(status)) inProgressCount++;
            validated.add(new TodoItem(item.id(), item.text().trim(), status));
        }
        if (inProgressCount > 1)
            return "Error: Only one task can be in_progress at a time";
        this.items = validated;
        return render();
    }
}
```

2. `TodoManager` 通过 `defaultTools()` 注册, `@Tool` 注解方法自动暴露为工具。

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem(system)
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                todoManager          // @Tool 注解方法自动注册
        )
        .build();
```

3. 系统提示注入: 每次用户输入时, 将最新 todo 状态注入系统提示, 并强调更新指令。

```java
// 动态系统提示：包含当前 todo 状态
String system = "You are a coding agent at " + workDir + ".\n"
        + "Use the todo tool to plan multi-step tasks. "
        + "Mark in_progress before starting, completed when done.\n"
        + "IMPORTANT: You MUST call updateTodos regularly.\n\n"
        + "<current-todos>\n" + todoManager.render() + "\n</current-todos>";
```

"同时只能有一个 in_progress" 强制顺序聚焦。系统提示中持续注入 todo 状态制造问责压力 -- 模型每次都能看到自己的计划, 不会忘记更新。

> **TIP**: Python 版在工具循环内追踪 `rounds_since_todo`, 连续 3 轮未调用 todo 时注入 `<reminder>` 文本。Spring AI 的 ChatClient 自动管理工具循环, 无法在循环内注入, 因此改用系统提示注入的方式实现同等效果。

## 相对 s02 的变更

| 组件           | 之前 (s02)       | 之后 (s03)                          |
|----------------|------------------|--------------------------------------|
| Tools          | 4                | 5 (+TodoManager `@Tool`)             |
| 规划           | 无               | 带状态的 TodoManager                 |
| 状态注入       | 无               | 系统提示注入 `<current-todos>`       |
| ChatClient     | 固定系统提示     | 每轮重建, 动态注入 todo 状态          |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s03.S03TodoWrite
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Refactor the file Hello.java: add JavaDoc, improve naming, and keep main method behavior unchanged`
2. `Create a Java package with utils and tests`
3. `Review all Java files and fix any style issues`
