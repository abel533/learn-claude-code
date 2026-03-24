# s02: Tool Use (工具使用)

`s01 > [ s02 ] s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"加一个工具, 只加一个 @Tool 方法"* -- 循环不用动, 新工具传入 `defaultTools()` 就行。
>
> **Harness 层**: 工具分发 -- 扩展模型能触达的边界。

## 问题

只有 `bash` 时, 所有操作都走 shell。`cat` 截断不可预测, `sed` 遇到特殊字符就崩, 每次 bash 调用都是不受约束的安全面。专用工具 (`read_file`, `write_file`) 可以在工具层面做路径沙箱。

关键洞察: 加工具不需要改循环。

## 解决方案

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

Spring AI 通过 @Tool 注解自动注册和分派。
无需手写 dispatch map，框架扫描工具对象的注解方法即可。
```

## 工作原理

1. 每个工具是一个独立的类，用 `@Tool` 注解声明。`PathValidator` 做路径沙箱防止逃逸工作区。

```java
// PathValidator —— 对应 Python 版的 safe_path() 函数
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

// ReadFileTool —— 对应 Python 版的 run_read() 函数
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

2. 工具注册只需传入 `defaultTools()`。Spring AI 扫描 `@Tool` 注解方法，自动完成名称映射和参数绑定。

```java
// 对应 Python 版的 TOOL_HANDLERS 字典
// Python: TOOL_HANDLERS = {"bash": fn, "read_file": fn, "write_file": fn, "edit_file": fn}
// Java:   只需传入工具对象，@Tool 注解自动注册
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(
                new BashTool(),       // bash 命令执行
                new ReadFileTool(),   // 文件读取
                new WriteFileTool(),  // 文件写入
                new EditFileTool()    // 文件编辑（查找替换）
        )
        .build();
```

3. 调用代码与 s01 完全一致。循环由框架管理，开发者只需关注工具实现。

```java
// 对比 s01，唯一变化是 defaultTools() 多传了 3 个工具对象
// 循环代码完全相同 —— 这正是 s02 的核心洞察
AgentRunner.interactive("s02", userMessage ->
        chatClient.prompt()
                .user(userMessage)
                .call()
                .content()
);
```

加工具 = 加一个 `@Tool` 类 + 传入 `defaultTools()`。循环永远不变。

> **TIPS — Python → Java 关键适配点:**
> - Python 的 `TOOL_HANDLERS` 字典 → Spring AI `@Tool` 注解 + `defaultTools()` 自动注册分派
> - Python 的 `safe_path()` 函数 → `PathValidator` 类（相同的路径逃逸检查逻辑）
> - Python 的 `lambda **kw` 参数解包 → `@ToolParam` 注解自动绑定参数
> - Python 的 `block.type == "tool_use"` 判断 → Spring AI 内部自动检测和分派

## 相对 s01 的变更

| 组件           | 之前 (s01)            | 之后 (s02)                             |
|----------------|-----------------------|----------------------------------------|
| Tools          | 1 (`BashTool`)        | 4 (`Bash`, `ReadFile`, `WriteFile`, `EditFile`) |
| Dispatch       | `defaultTools(bash)`  | `defaultTools(bash, read, write, edit)` |
| 路径安全       | 无                    | `PathValidator` 沙箱                   |
| Agent loop     | 不变                  | 不变                                   |

```java
// s01 → s02 唯一变化: defaultTools() 多传了 3 个工具对象
.defaultTools(
        new BashTool(),
        new ReadFileTool(),    // +新增
        new WriteFileTool(),   // +新增
        new EditFileTool()     // +新增
)
```

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s02.S02ToolUse
```

> 运行前需设置环境变量: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Read the file pom.xml`
2. `Create a file called Greet.java with a greet(name) method`
3. `Edit Greet.java to add a Javadoc comment to the method`
4. `Read Greet.java to verify the edit worked`
