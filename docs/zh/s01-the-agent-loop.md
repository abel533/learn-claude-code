# s01: The Agent Loop (智能体循环)

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- 一个工具 + 一个循环 = 一个智能体。
>
> **Harness 层**: 循环 -- 模型与真实世界的第一道连接。

## 问题

语言模型能推理代码, 但碰不到真实世界 -- 不能读文件、跑测试、看报错。没有循环, 每次工具调用你都得手动把结果粘回去。你自己就是那个循环。

## 解决方案

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() 自动循环直到无工具调用)
```

一个 `call()` 调用控制整个流程。Spring AI 自动循环, 直到模型不再调用工具。

## 工作原理

### 1. 构建 ChatClient：注入模型 + 注册工具

通过 Spring Boot 自动配置注入 `ChatModel`，用 `ChatClient.builder()` 构建客户端，设置系统提示和工具。

```java
// TIP: Python 版在模块级创建 client = Anthropic() 和 MODEL。
// Spring AI 通过自动配置注入 ChatModel，再用 builder 构建 ChatClient。
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // @Tool 注解的工具对象
            .build();
}
```

### 2. `@Tool` 注解：声明式工具注册

Spring AI 通过 `@Tool` 注解自动发现和注册工具。框架在启动时扫描 `defaultTools()` 传入的对象，提取所有 `@Tool` 方法的签名和描述，生成 LLM 需要的 tool schema（名称、参数、描述），然后在每次 `call()` 请求中自动携带。

```java
// BashTool —— 对应 Python 版的 run_bash() 函数
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // 危险命令检查 + ProcessBuilder 执行 + 超时控制 + 输出截断
        // ...
    }
}
```

> 对比 Python 版的手动注册方式：
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: 只需 `@Tool` + `@ToolParam` 注解，框架自动完成 schema 生成和方法分派

### 3. Spring AI 内部自动循环：`call()` 的底层实现

**这是理解 Java 版与 Python 版最关键的区别。** Python 版本需要手写 while 循环来驱动工具调用：

```python
# Python 版 —— 手动循环
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # 收集 assistant 消息
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # 模型不再调用工具，退出循环
        # 执行工具并回传结果
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI 的 `ChatClient.call()` **内部封装了完全等价的逻辑**：

```
call() 内部流程:
  ┌─────────────────────────────────────────────────────┐
  │  1. 组装请求: system prompt + user message + tools  │
  │  2. 发送给 LLM                                     │
  │  3. 解析响应                                        │
  │     ├── 有 tool_use? ──→ 是:                       │
  │     │   a. 提取工具名和参数                         │
  │     │   b. 通过反射调用对应的 @Tool 方法            │
  │     │   c. 将 tool_result 追加到消息列表            │
  │     │   d. 回到步骤 2（自动循环）                   │
  │     └── 否 ──→ 返回最终文本                        │
  └─────────────────────────────────────────────────────┘
```

关键点：
- **工具检测**: Spring AI 检查响应中是否有 `tool_use` 类型的 content block（对应 Python 的 `stop_reason == "tool_use"`）
- **反射分派**: 框架通过 Java 反射机制，根据 LLM 返回的工具名称找到对应的 `@Tool` 方法并调用（对应 Python 的 `TOOL_HANDLERS[block.name]`）
- **结果回传**: 工具执行结果自动包装为 `tool_result` 消息追加到对话（对应 Python 手动构造 `tool_result` content block）
- **循环终止**: 当模型返回纯文本（无工具调用）时，`call()` 返回最终结果

因此，Python 版约 15 行的 while 循环，在 Java 版中浓缩为一行 `.call()`。

### 4. `AgentRunner.interactive()`：REPL 交互循环

`AgentRunner` 是所有课程共用的交互式 REPL（Read-Eval-Print Loop）工具类，对应 Python 版 `if __name__ == "__main__"` 中的 `input()` 循环。

```java
public class AgentRunner {
    /**
     * 启动交互式 REPL 循环。
     * @param prefix  提示符前缀（如 "s01"）
     * @param handler 处理用户输入并返回 Agent 响应的函数
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("输入 'q' 或 'exit' 退出");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // 彩色提示符
            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().trim();
            } catch (Exception e) {
                break;
            }
            if (input.isEmpty() || "exit".equalsIgnoreCase(input) || "q".equalsIgnoreCase(input)) {
                break;
            }
            try {
                String response = handler.apply(input);  // 调用 Agent 处理
                if (response != null && !response.isBlank()) {
                    System.out.println(response);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        System.out.println("Bye!");
    }
}
```

工作流程：`Scanner` 读取输入 → `handler.apply()` 发给 Agent → 打印响应 → 循环。`handler` 是一个函数式接口，每个课程传入自己的 Agent 调用逻辑。

### 5. 组装为完整的 Agent 类

```java
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S01AgentLoop implements CommandLineRunner {

    private final ChatClient chatClient;

    public S01AgentLoop(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at ...")
                .defaultTools(new BashTool())
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s01", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()       // ← 这一个调用 = Python 的整个 while 循环
                        .content()
        );
    }
}
```

> **TIPS — Python → Java 关键适配点:**
> - Python 的 `while True` + `stop_reason` 手动循环 → Spring AI `ChatClient.call()` 内置自动循环
> - Python 的 `TOOLS` 数组 + `TOOL_HANDLERS` 字典 → `@Tool` 注解 + `defaultTools()` 自动注册与反射分派
> - Python 的 `client = Anthropic()` → Spring Boot 自动配置注入 `ChatModel`
> - Python 的 `input()` 交互 → `AgentRunner.interactive()` 封装 Scanner REPL + 函数式接口

不到 40 行核心代码, 这就是整个智能体。后面 11 个章节都在这个循环上叠加机制 -- 循环本身始终不变。

## 变更内容

| 组件          | 之前       | 之后                                             |
|---------------|------------|--------------------------------------------------|
| Agent loop    | (无)       | `ChatClient.call()` 内置工具循环                 |
| Tools         | (无)       | `BashTool` (单一 `@Tool` 工具)                   |
| Messages      | (无)       | Spring AI 内部管理消息列表                       |
| Control flow  | (无)       | 框架自动判断: 无工具调用时返回最终文本           |

```java
// 核心代码 —— 构建 + 调用
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> 运行前需设置环境变量: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **当前默认使用 OpenAI 协议**（兼容所有 OpenAI API 格式的服务，包括 OpenAI 官方、Azure OpenAI、各类第三方大模型服务的 OpenAI 兼容接口等）。
> 如需使用 Anthropic 协议（Claude 系列模型原生接口），请展开下方「切换 AI 协议」。

<details>
<summary><strong>切换 AI 协议（OpenAI ↔ Anthropic）</strong></summary>

本项目通过 Spring AI 的 **Starter 依赖 + 配置文件** 来切换底层协议，Java 业务代码（`ChatModel`、`ChatClient`）**无需任何修改**。

#### 方式一：OpenAI 协议（默认）

`pom.xml` 依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` 配置：

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-xxx}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
```

环境变量示例（以 OpenAI 官方为例）：

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # 可替换为任何 OpenAI 兼容接口
export AI_MODEL=gpt-4o
```

> **TIP**: 许多第三方大模型服务（如 DeepSeek、Mistral、通义千问等）提供了 OpenAI 兼容接口，只需修改 `AI_BASE_URL` 和 `AI_MODEL` 即可接入，无需切换协议。

#### 方式二：Anthropic 协议（Claude 原生接口）

**第 1 步**：修改 `pom.xml`，将 OpenAI starter 替换为 Anthropic starter：

```xml
<!-- 注释或删除 OpenAI starter -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- 添加 Anthropic starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**第 2 步**：修改 `application.yml`，将 `spring.ai.openai` 替换为 `spring.ai.anthropic`：

```yaml
spring:
  ai:
    anthropic:
      api-key: ${AI_API_KEY}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
```

**第 3 步**：设置环境变量：

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### 切换原理

Spring AI 的设计使得 `ChatModel` 是一个统一的抽象接口。不同的 Starter 提供不同的实现：

| Starter 依赖 | 自动注入的 ChatModel 实现 | 配置前缀 |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

业务代码始终面向 `ChatModel` 接口编程，切换协议只需替换依赖和配置，无需改动任何 Java 代码。

</details>

试试这些 prompt(英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`
