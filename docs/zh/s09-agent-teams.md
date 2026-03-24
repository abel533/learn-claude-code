# s09: Agent Teams (智能体团队)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"任务太大一个人干不完, 要能分给队友"* -- 持久化队友 + JSONL 邮箱。
>
> **Harness 层**: 团队邮箱 -- 多个模型, 通过文件协调。

## 问题

子智能体 (s04) 是一次性的: 生成、干活、返回摘要、消亡。没有身份, 没有跨调用的记忆。后台任务 (s08) 能跑 shell 命令, 但做不了 LLM 引导的决策。

真正的团队协作需要三样东西: (1) 能跨多轮对话存活的持久智能体, (2) 身份和生命周期管理, (3) 智能体之间的通信通道。

## 解决方案

```
Teammate lifecycle:
  spawn -> WORKING -> IDLE -> WORKING -> ... -> SHUTDOWN

Communication:
  .team/
    config.json           <- team roster + statuses
    inbox/
      alice.jsonl         <- append-only, drain-on-read
      bob.jsonl
      lead.jsonl

              +--------+    send("alice","bob","...")    +--------+
              | alice  | -----------------------------> |  bob   |
              | loop   |    bob.jsonl << {json_line}    |  loop  |
              +--------+                                +--------+
                   ^                                         |
                   |        BUS.read_inbox("alice")          |
                   +---- alice.jsonl -> read + drain ---------+
```

## 工作原理

1. TeammateManager 通过 config.json 维护团队名册。

```java
// src/main/java/io/mybatis/learn/s09/TeammateManager.java
public class TeammateManager {
    private final ChatModel chatModel;
    private final MessageBus bus;
    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> config;
    // Python用threading.Thread + dict; Java用ConcurrentHashMap天然线程安全
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();

    public TeammateManager(ChatModel chatModel, MessageBus bus, Path teamDir) {
        this.chatModel = chatModel;
        this.bus = bus;
        this.configPath = teamDir.resolve("config.json");
        Files.createDirectories(teamDir);
        this.config = loadConfig();
    }
```

2. `spawn()` 创建队友并在线程中启动 agent loop。

```java
// Python用threading.Thread; Java用Thread.startVirtualThread()虚拟线程
public synchronized String spawn(String name, String role, String prompt) {
    Map<String, Object> member = new LinkedHashMap<>();
    member.put("name", name);
    member.put("role", role);
    member.put("status", "working");
    ((List<Map<String, Object>>) config.get("members")).add(member);
    saveConfig();

    // 虚拟线程：轻量级，由JVM调度，不占用OS线程
    Thread thread = Thread.startVirtualThread(
            () -> teammateLoop(name, role, prompt));
    threads.put(name, thread);
    return "Spawned '" + name + "' (role: " + role + ")";
}
```

3. MessageBus: append-only 的 JSONL 收件箱。`send()` 追加一行; `read_inbox()` 读取全部并清空。

```java
// src/main/java/io/mybatis/learn/core/team/MessageBus.java
// Python靠GIL隐式保证线程安全; Java用synchronized显式保证
public class MessageBus {
    private final Path inboxDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public synchronized String send(String sender, String to, String content,
                                    String msgType, Map<String, Object> extra) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", msgType);
        msg.put("from", sender);
        msg.put("content", content);
        msg.put("timestamp", System.currentTimeMillis() / 1000.0);
        if (extra != null) msg.putAll(extra);

        Path inbox = inboxDir.resolve(to + ".jsonl");
        Files.writeString(inbox, mapper.writeValueAsString(msg) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "Sent " + msgType + " to " + to;
    }

    public synchronized List<Map<String, Object>> readInbox(String name) {
        Path inbox = inboxDir.resolve(name + ".jsonl");
        if (!Files.exists(inbox)) return List.of();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : Files.readAllLines(inbox)) {
            if (!line.isBlank())
                messages.add(mapper.readValue(line, new TypeReference<>() {}));
        }
        Files.writeString(inbox, "");  // drain
        return messages;
    }
}
```

4. 每个队友在每次 `call()` 调用间检查收件箱, 将消息注入上下文。ChatClient 的 `call()` 等价于 Python 的完整工具循环（循环到 `stop_reason != "tool_use"` 为止）。

```java
// Python队友在每次LLM调用前检查收件箱; Java在每次call()调用间检查
protected void teammateLoop(String name, String role, String initialPrompt) {
    String sysPrompt = String.format(
            "You are '%s', role: %s. Use send_message to communicate.",
            name, role);

    var messageTool = new TeammateMessageTool(bus, name);
    ChatClient client = ChatClient.builder(chatModel)
            .defaultSystem(sysPrompt)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), messageTool)
            .build();

    // 初始工作（call() = 完整工具链，等价于Python循环到stop_reason != "tool_use"）
    String response = client.prompt(initialPrompt).call().content();

    // 每次call()之间检查收件箱（而非Python的每次LLM调用之间）
    for (int round = 0; round < 50; round++) {
        Thread.sleep(2000);
        var inbox = bus.readInbox(name);
        if (inbox.isEmpty()) break;
        String inboxJson = mapper.writeValueAsString(inbox);
        response = client.prompt("<inbox>" + inboxJson + "</inbox>").call().content();
    }
    setStatus(name, "idle");
}
```

## 相对 s08 的变更

| 组件           | 之前 (s08)       | 之后 (s09)                         |
|----------------|------------------|------------------------------------|
| Tools          | 6                | 9 (+spawn/send/read_inbox)         |
| 智能体数量     | 单一             | 领导 + N 个队友                    |
| 持久化         | 无               | config.json + JSONL 收件箱         |
| 线程           | 后台命令         | 每线程完整 agent loop              |
| 生命周期       | 一次性           | idle -> working -> idle            |
| 通信           | 无               | message + broadcast                |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s09.S09AgentTeams
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Spawn alice (coder) and bob (tester). Have alice send bob a message.`
2. `Broadcast "status update: phase 1 complete" to all teammates`
3. `Check the lead inbox for any messages`
4. 输入 `/team` 查看团队名册和状态
5. 输入 `/inbox` 手动检查领导的收件箱
