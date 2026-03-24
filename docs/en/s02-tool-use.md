# s02: Tool Use

`s01 > [ s02 ] s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"Adding a tool means adding one @Tool method"* -- the loop stays the same; new tools are passed into `defaultTools()`.
>
> **Harness layer**: Tool dispatch -- expanding what the model can reach.

## Problem

With only `bash`, the agent shells out for everything. `cat` truncates unpredictably, `sed` fails on special characters, and every bash call is an unconstrained security surface. Dedicated tools (`read_file`, `write_file`) let you enforce path sandboxing at the tool level.

The key insight: adding tools does not require changing the loop.

## Solution

```
+--------+      +-------+      +--------------------+
|  User  | ---> |  LLM  | ---> | defaultTools()     |
| prompt |      |       |      | {                  |
+--------+      +---+---+      |   BashTool         |
                    ^           |   ReadFileTool     |
                    |           |   WriteFileTool    |
                    +-----------+   EditFileTool     |
                    tool_result | }                  |
                                +--------------------+

Spring AI auto-registers and dispatches via @Tool annotations.
No hand-written dispatch map needed -- the framework scans annotated methods on tool objects.
```

## How It Works

1. Each tool is a standalone class declared with `@Tool` annotation. `PathValidator` provides path sandboxing to prevent workspace escape.

```java
// PathValidator -- corresponds to the Python version's safe_path() function
public class PathValidator {
    private final Path workDir;

    public Path resolve(String relativePath) {
        Path resolved = workDir.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }
}

// ReadFileTool -- corresponds to the Python version's run_read() function
public class ReadFileTool {
    private final PathValidator pathValidator;

    @Tool(description = "Read file contents. Optionally limit the number of lines returned.")
    public String readFile(
            @ToolParam(description = "Relative path to the file") String path,
            @ToolParam(description = "Maximum number of lines to read", required = false) Integer limit) {
        Path filePath = pathValidator.resolve(path);
        List<String> lines = Files.readAllLines(filePath);
        if (limit != null && limit > 0 && limit < lines.size()) {
            lines = lines.subList(0, limit);
        }
        return String.join("\n", lines);
    }
}
```

2. Tool registration simply passes objects to `defaultTools()`. Spring AI scans `@Tool` annotated methods and automatically handles name mapping and parameter binding.

```java
// Corresponds to the Python version's TOOL_HANDLERS dict
// Python: TOOL_HANDLERS = {"bash": fn, "read_file": fn, "write_file": fn, "edit_file": fn}
// Java:   Just pass tool objects; @Tool annotations handle auto-registration
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(
                new BashTool(),       // bash command execution
                new ReadFileTool(),   // file reading
                new WriteFileTool(),  // file writing
                new EditFileTool()    // file editing (find & replace)
        )
        .build();
```

3. The calling code is identical to s01. The loop is managed by the framework; developers only focus on tool implementation.

```java
// Compared to s01, the only change is that defaultTools() receives 3 more tool objects
// The loop code is exactly the same -- this is the core insight of s02
AgentRunner.interactive("s02", userMessage ->
        chatClient.prompt()
                .user(userMessage)
                .call()
                .content()
);
```

Add a tool = add a `@Tool` class + pass it to `defaultTools()`. The loop never changes.

> **TIPS — Key Python → Java Adaptations:**
> - Python's `TOOL_HANDLERS` dict → Spring AI `@Tool` annotation + `defaultTools()` auto-registration and dispatch
> - Python's `safe_path()` function → `PathValidator` class (same path escape check logic)
> - Python's `lambda **kw` parameter unpacking → `@ToolParam` annotation auto-binds parameters
> - Python's `block.type == "tool_use"` check → Spring AI handles detection and dispatch internally

## What Changed From s01

| Component      | Before (s01)          | After (s02)                                    |
|----------------|-----------------------|------------------------------------------------|
| Tools          | 1 (`BashTool`)        | 4 (`Bash`, `ReadFile`, `WriteFile`, `EditFile`) |
| Dispatch       | `defaultTools(bash)`  | `defaultTools(bash, read, write, edit)`         |
| Path safety    | None                  | `PathValidator` sandbox                         |
| Agent loop     | Unchanged             | Unchanged                                       |

```java
// s01 → s02 only change: defaultTools() receives 3 more tool objects
.defaultTools(
        new BashTool(),
        new ReadFileTool(),    // +new
        new WriteFileTool(),   // +new
        new EditFileTool()     // +new
)
```

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s02.S02ToolUse
```

> Set environment variables before running: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Read the file pom.xml`
2. `Create a file called Greet.java with a greet(name) method`
3. `Edit Greet.java to add a Javadoc comment to the method`
4. `Read Greet.java to verify the edit worked`
