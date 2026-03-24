# s01: The Agent Loop

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- one tool + one loop = an agent.
>
> **Harness layer**: The loop -- the model's first connection to the real world.

## Problem

A language model can reason about code, but it can't *touch* the real world -- can't read files, run tests, or check errors. Without a loop, every tool call requires you to manually copy-paste results back. You become the loop.

## Solution

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() auto-loops until no tool calls)
```

A single `call()` invocation controls the entire flow. Spring AI loops automatically until the model stops calling tools.

## How It Works

### 1. Build ChatClient: Inject Model + Register Tools

Inject `ChatModel` via Spring Boot auto-configuration, build the client with `ChatClient.builder()`, set the system prompt and tools.

```java
// TIP: The Python version creates client = Anthropic() and MODEL at module level.
// Spring AI injects ChatModel via auto-configuration, then builds ChatClient with builder.
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // Tool object with @Tool annotation
            .build();
}
```

### 2. `@Tool` Annotation: Declarative Tool Registration

Spring AI automatically discovers and registers tools via the `@Tool` annotation. At startup, the framework scans objects passed to `defaultTools()`, extracts all `@Tool` method signatures and descriptions, generates the tool schema the LLM needs (name, parameters, description), and automatically includes it in every `call()` request.

```java
// BashTool -- corresponds to the Python version's run_bash() function
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // Dangerous command check + ProcessBuilder execution + timeout control + output truncation
        // ...
    }
}
```

> Comparison with Python's manual registration:
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: Just `@Tool` + `@ToolParam` annotations; the framework auto-generates schemas and dispatches methods

### 3. Spring AI Internal Auto-Loop: How `call()` Works Under the Hood

**This is the most critical difference between the Java and Python versions.** The Python version requires a hand-written while loop to drive tool calls:

```python
# Python version -- manual loop
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # Collect assistant message
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # Model no longer calling tools, exit loop
        # Execute tools and feed back results
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI's `ChatClient.call()` **encapsulates fully equivalent logic internally**:

```
call() internal flow:
  ┌─────────────────────────────────────────────────────┐
  │  1. Assemble request: system prompt + user msg + tools │
  │  2. Send to LLM                                     │
  │  3. Parse response                                   │
  │     ├── Has tool_use? ──→ Yes:                      │
  │     │   a. Extract tool name and arguments           │
  │     │   b. Invoke corresponding @Tool method via reflection │
  │     │   c. Append tool_result to message list        │
  │     │   d. Go back to step 2 (auto-loop)            │
  │     └── No ──→ Return final text                    │
  └─────────────────────────────────────────────────────┘
```

Key points:
- **Tool detection**: Spring AI checks if the response contains `tool_use` content blocks (equivalent to Python's `stop_reason == "tool_use"`)
- **Reflection dispatch**: The framework uses Java reflection to find and invoke the `@Tool` method matching the tool name returned by the LLM (equivalent to Python's `TOOL_HANDLERS[block.name]`)
- **Result feedback**: Tool execution results are automatically wrapped as `tool_result` messages and appended to the conversation (equivalent to Python's manual `tool_result` content block construction)
- **Loop termination**: When the model returns pure text (no tool calls), `call()` returns the final result

Thus, Python's ~15-line while loop is condensed into a single `.call()` in Java.

### 4. `AgentRunner.interactive()`: The REPL Interaction Loop

`AgentRunner` is a shared REPL (Read-Eval-Print Loop) utility class used across all lessons, corresponding to the `input()` loop in Python's `if __name__ == "__main__"` block.

```java
public class AgentRunner {
    /**
     * Start an interactive REPL loop.
     * @param prefix  Prompt prefix (e.g., "s01")
     * @param handler Function that processes user input and returns Agent response
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Type 'q' or 'exit' to quit");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // Colored prompt
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
                String response = handler.apply(input);  // Call Agent handler
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

Workflow: `Scanner` reads input → `handler.apply()` sends to Agent → print response → loop. The `handler` is a functional interface; each lesson passes in its own Agent invocation logic.

### 5. Assembled into a Complete Agent Class

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
                        .call()       // ← This single call = Python's entire while loop
                        .content()
        );
    }
}
```

> **TIPS — Key Python → Java Adaptations:**
> - Python's `while True` + `stop_reason` manual loop → Spring AI `ChatClient.call()` built-in auto-loop
> - Python's `TOOLS` array + `TOOL_HANDLERS` dict → `@Tool` annotation + `defaultTools()` auto-registration with reflection dispatch
> - Python's `client = Anthropic()` → Spring Boot auto-configured `ChatModel` injection
> - Python's `input()` interaction → `AgentRunner.interactive()` wrapping Scanner REPL + functional interface

Under 40 lines of core code, and that's the entire agent. The next 11 chapters all layer mechanisms on top of this loop -- the loop itself never changes.

## What Changed

| Component     | Before     | After                                          |
|---------------|------------|-------------------------------------------------|
| Agent loop    | (none)     | `ChatClient.call()` built-in tool loop          |
| Tools         | (none)     | `BashTool` (single `@Tool` tool)                |
| Messages      | (none)     | Managed internally by Spring AI                 |
| Control flow  | (none)     | Framework auto-detects: returns final text when no tool calls |

```java
// Core code -- build + call
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> Set environment variables before running: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **The default protocol is OpenAI** (compatible with all OpenAI API-format services, including OpenAI official, Azure OpenAI, and any third-party model services offering an OpenAI-compatible interface).
> To use the Anthropic protocol (Claude native API), expand the section below.

<details>
<summary><strong>Switching AI Protocols (OpenAI ↔ Anthropic)</strong></summary>

This project switches the underlying protocol via **Spring AI Starter dependency + configuration file**. Java business code (`ChatModel`, `ChatClient`) **requires no changes**.

#### Option 1: OpenAI Protocol (Default)

`pom.xml` dependency:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` configuration:

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

Environment variable example:

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # Replace with any OpenAI-compatible endpoint
export AI_MODEL=gpt-4o
```

> **TIP**: Many third-party model services (e.g., DeepSeek, Mistral, Qwen) provide OpenAI-compatible APIs. Simply change `AI_BASE_URL` and `AI_MODEL` to connect — no protocol switch needed.

#### Option 2: Anthropic Protocol (Claude Native API)

**Step 1**: Edit `pom.xml` — replace the OpenAI starter with the Anthropic starter:

```xml
<!-- Comment out or remove the OpenAI starter -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- Add the Anthropic starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**Step 2**: Edit `application.yml` — replace `spring.ai.openai` with `spring.ai.anthropic`:

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

**Step 3**: Set environment variables:

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### How Switching Works

Spring AI's `ChatModel` is a unified abstraction interface. Different Starters provide different implementations:

| Starter Dependency | Auto-injected ChatModel | Config Prefix |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

Business code always programs against the `ChatModel` interface. Switching protocols only requires changing the dependency and configuration — no Java code changes needed.

</details>

Try these prompts(English prompts work better with LLMs, but Chinese also works):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`
