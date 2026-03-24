# s03: TodoWrite

`s01 > s02 > [ s03 ] s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"An agent without a plan drifts"* -- list the steps first, then execute. Doubles the completion rate.
>
> **Harness layer**: Planning -- keeping the model on course without scripting the route.

## Problem

On multi-step tasks, the model loses track -- repeats work, skips steps, or wanders off. Long conversations make this worse: tool results keep filling the context, gradually diluting the system prompt's influence. A 10-step refactoring might complete steps 1-3, then the model starts improvising because steps 4-10 have been pushed out of attention.

## Solution

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
              Inject latest todo state into
                system prompt via defaultSystem()
                on each request
```

## How It Works

1. TodoManager stores items with statuses. Only one item can be `in_progress` at a time.

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

2. `TodoManager` is registered via `defaultTools()`; the `@Tool` annotated method is automatically exposed as a tool.

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem(system)
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                todoManager          // @Tool annotated method auto-registered
        )
        .build();
```

3. System prompt injection: on each user input, inject the latest todo state into the system prompt with emphasis on update instructions.

```java
// Dynamic system prompt: includes current todo state
String system = "You are a coding agent at " + workDir + ".\n"
        + "Use the todo tool to plan multi-step tasks. "
        + "Mark in_progress before starting, completed when done.\n"
        + "IMPORTANT: You MUST call updateTodos regularly.\n\n"
        + "<current-todos>\n" + todoManager.render() + "\n</current-todos>";
```

The "only one in_progress at a time" constraint forces sequential focus. Continuously injecting todo state into the system prompt creates accountability pressure -- the model sees its own plan every turn and won't forget to update it.

> **TIP**: The Python version tracks `rounds_since_todo` inside the tool loop and injects `<reminder>` text after 3 consecutive rounds without a todo call. Spring AI's ChatClient manages the tool loop automatically and doesn't allow mid-loop injection, so system prompt injection is used instead to achieve the same effect.

## What Changed From s02

| Component      | Before (s02)     | After (s03)                          |
|----------------|------------------|--------------------------------------|
| Tools          | 4                | 5 (+TodoManager `@Tool`)             |
| Planning       | None             | TodoManager with statuses            |
| State injection| None             | System prompt injection `<current-todos>` |
| ChatClient     | Fixed system prompt | Rebuilt each turn, dynamic todo state injection |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s03.S03TodoWrite
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Refactor the file Hello.java: add JavaDoc, improve naming, and keep main method behavior unchanged`
2. `Create a Java package with utils and tests`
3. `Review all Java files and fix any style issues`
