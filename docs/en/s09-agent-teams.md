# s09: Agent Teams

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"When the task is too big for one, delegate to teammates"* -- persistent teammates + JSONL mailboxes.
>
> **Harness layer**: Team mailboxes -- multiple models, coordinated through files.

## Problem

Subagents (s04) are disposable: spawn, work, return summary, die. No identity, no memory between invocations. Background tasks (s08) run shell commands but can't make LLM-guided decisions.

Real teamwork needs three things: (1) persistent agents that outlive a single prompt, (2) identity and lifecycle management, (3) a communication channel between agents.

## Solution

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

## How It Works

1. TeammateManager maintains the team roster via config.json.

```java
// src/main/java/io/mybatis/learn/s09/TeammateManager.java
public class TeammateManager {
    private final ChatModel chatModel;
    private final MessageBus bus;
    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> config;
    // Python uses threading.Thread + dict; Java uses ConcurrentHashMap for natural thread safety
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();

    public TeammateManager(ChatModel chatModel, MessageBus bus, Path teamDir) {
        this.chatModel = chatModel;
        this.bus = bus;
        this.configPath = teamDir.resolve("config.json");
        Files.createDirectories(teamDir);
        this.config = loadConfig();
    }
```

2. `spawn()` creates a teammate and starts its agent loop in a thread.

```java
// Python uses threading.Thread; Java uses Thread.startVirtualThread() for virtual threads
public synchronized String spawn(String name, String role, String prompt) {
    Map<String, Object> member = new LinkedHashMap<>();
    member.put("name", name);
    member.put("role", role);
    member.put("status", "working");
    ((List<Map<String, Object>>) config.get("members")).add(member);
    saveConfig();

    // Virtual thread: lightweight, JVM-scheduled, doesn't occupy OS threads
    Thread thread = Thread.startVirtualThread(
            () -> teammateLoop(name, role, prompt));
    threads.put(name, thread);
    return "Spawned '" + name + "' (role: " + role + ")";
}
```

3. MessageBus: append-only JSONL inboxes. `send()` appends a JSON line; `read_inbox()` reads all and drains.

```java
// src/main/java/io/mybatis/learn/core/team/MessageBus.java
// Python relies on GIL for implicit thread safety; Java uses synchronized for explicit safety
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

4. Each teammate checks its inbox between `call()` invocations, injecting messages into context. ChatClient's `call()` is equivalent to Python's full tool loop (looping until `stop_reason != "tool_use"`).

```java
// Python teammates check inbox before each LLM call; Java checks between each call()
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

    // Initial work (call() = full tool chain, equivalent to Python loop until stop_reason != "tool_use")
    String response = client.prompt(initialPrompt).call().content();

    // Check inbox between each call() (vs. Python's between each LLM call)
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

## What Changed From s08

| Component      | Before (s08)     | After (s09)                        |
|----------------|------------------|------------------------------------|
| Tools          | 6                | 9 (+spawn/send/read_inbox)         |
| Agents         | Single           | Lead + N teammates                 |
| Persistence    | None             | config.json + JSONL inboxes        |
| Threads        | Background cmds  | Full agent loops per thread        |
| Lifecycle      | Fire-and-forget  | idle -> working -> idle            |
| Communication  | None             | message + broadcast                |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s09.S09AgentTeams
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Spawn alice (coder) and bob (tester). Have alice send bob a message.`
2. `Broadcast "status update: phase 1 complete" to all teammates`
3. `Check the lead inbox for any messages`
4. Type `/team` to see the team roster with statuses
5. Type `/inbox` to manually check the lead's inbox
