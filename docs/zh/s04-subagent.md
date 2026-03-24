# s04: Subagents (子智能体)

`s01 > s02 > s03 > [ s04 ] s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"大任务拆小, 每个小任务干净的上下文"* -- 子智能体用独立 messages[], 不污染主对话。
>
> **Harness 层**: 上下文隔离 -- 守护模型的思维清晰度。

## 问题

智能体工作越久, messages 数组越胖。每次读文件、跑命令的输出都永久留在上下文里。"这个项目用什么测试框架?" 可能要读 5 个文件, 但父智能体只需要一个词: "pytest。"

## 解决方案

```
Parent agent                     Subagent
+------------------+             +------------------+
| messages=[...]   |             | messages=[]      | <-- fresh
|                  |  dispatch   |                  |
| tool: task       | ----------> | while tool_use:  |
|   prompt="..."   |             |   call tools     |
|                  |  summary    |   append results |
|   result = "..." | <---------- | return last text |
+------------------+             +------------------+

Parent context stays clean. Subagent context is discarded.
```

## 工作原理

1. 父智能体有一个 `task` 工具。子智能体拥有除 `task` 外的所有基础工具 (禁止递归生成)。

```java
// 父 Agent：拥有基础工具 + SubagentTool
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent. "
                + "Use the task tool to delegate subtasks.")
        .defaultTools(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new SubagentTool(chatModel)  // 父 Agent 独有
        )
        .build();
```

2. 子智能体以全新的 `ChatClient` 启动, 拥有独立上下文。只有最终文本返回给父智能体。

```java
@Tool(description = "Spawn a subagent with fresh context. "
        + "Use for exploration or subtasks that might pollute the main context.")
public String task(
        @ToolParam(description = "The task prompt") String prompt,
        @ToolParam(description = "Short description", required = false)
        String description) {

    // 创建全新的 ChatClient —— 这就是"上下文隔离"的全部
    ChatClient subClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding subagent. "
                    + "Complete the task, then summarize findings.")
            .defaultTools(          // 基础工具, 没有 task (防止递归)
                    new BashTool(),
                    new ReadFileTool(),
                    new WriteFileTool(),
                    new EditFileTool()
            )
            .build();

    String result = subClient.prompt()
            .user(prompt)
            .call()
            .content();

    // 只返回最终文本, 子 Agent 上下文被丢弃
    return (result != null) ? result : "(no summary)";
}
```

子智能体可能跑了多次工具调用, 但整个消息历史直接丢弃。父智能体收到的只是一段摘要文本, 作为普通 `tool_result` 返回。Spring AI 的 `ChatClient.call()` 内部管理工具循环, 无需手动限制迭代次数。

## 相对 s03 的变更

| 组件           | 之前 (s03)       | 之后 (s04)                           |
|----------------|------------------|---------------------------------------|
| Tools          | 5                | 5 (基础) + SubagentTool (仅父端)     |
| 上下文         | 单一共享         | 父 + 子隔离 (独立 ChatClient)        |
| Subagent       | 无               | `SubagentTool.task()` 方法           |
| 返回值         | 不适用           | 仅摘要文本                           |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s04.S04Subagent
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Use a subtask to find what testing framework this project uses`
2. `Delegate: read all .java files and summarize what each one does`
3. `Use a task to create a new module, then verify it from here`
