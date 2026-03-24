# s09: Agent Teams (エージェントチーム)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"一人で終わらないなら、チームメイトに任せる"* -- 永続チームメイト + JSONL メールボックス。
>
> **Harness 層**: チームメールボックス -- 複数モデルをファイルで協調。

## 問題

サブエージェント (s04) は使い捨てだ: 生成し、作業し、要約を返し、消滅する。アイデンティティもなく、呼び出し間の記憶もない。バックグラウンドタスク (s08) はシェルコマンドを実行するが、LLM 誘導の意思決定はできない。

本物のチームワークには3つが必要: (1) 複数ターンの会話を超えて存続する永続エージェント、(2) アイデンティティとライフサイクル管理、(3) エージェント間の通信チャネル。

## 解決策

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

## 仕組み

1. TeammateManager が config.json でチーム名簿を管理する。

```java
// src/main/java/io/mybatis/learn/s09/TeammateManager.java
public class TeammateManager {
    private final ChatModel chatModel;
    private final MessageBus bus;
    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> config;
    // Python は threading.Thread + dict を使用、Java は ConcurrentHashMap で天然スレッドセーフ
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();

    public TeammateManager(ChatModel chatModel, MessageBus bus, Path teamDir) {
        this.chatModel = chatModel;
        this.bus = bus;
        this.configPath = teamDir.resolve("config.json");
        Files.createDirectories(teamDir);
        this.config = loadConfig();
    }
```

2. `spawn()` がチームメイトを作成し、スレッド内でエージェントループを開始する。

```java
// Python は threading.Thread を使用、Java は Thread.startVirtualThread() 仮想スレッドを使用
public synchronized String spawn(String name, String role, String prompt) {
    Map<String, Object> member = new LinkedHashMap<>();
    member.put("name", name);
    member.put("role", role);
    member.put("status", "working");
    ((List<Map<String, Object>>) config.get("members")).add(member);
    saveConfig();

    // 仮想スレッド: 軽量、JVM スケジュール、OS スレッドを占有しない
    Thread thread = Thread.startVirtualThread(
            () -> teammateLoop(name, role, prompt));
    threads.put(name, thread);
    return "Spawned '" + name + "' (role: " + role + ")";
}
```

3. MessageBus: 追記専用の JSONL インボックス。`send()` が1行を追記し、`read_inbox()` がすべて読み取ってドレインする。

```java
// src/main/java/io/mybatis/learn/core/team/MessageBus.java
// Python は GIL で暗黙的にスレッドセーフ、Java は synchronized で明示的に保証
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

4. 各チームメイトは `call()` 呼び出し間でインボックスをチェックし、メッセージをコンテキストに注入する。ChatClient の `call()` は Python の完全なツールループ（`stop_reason != "tool_use"` まで繰り返す）に相当する。

```java
// Python のチームメイトは毎回の LLM 呼び出し前にインボックスをチェック、Java は毎回の call() 呼び出し間でチェック
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

    // 初期作業（call() = 完全なツールチェーン、Python の stop_reason != "tool_use" までのループに相当）
    String response = client.prompt(initialPrompt).call().content();

    // 毎回の call() 間でインボックスをチェック（Python の毎回の LLM 呼び出し間ではなく）
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

## s08 からの変更点

| コンポーネント   | 変更前 (s08)     | 変更後 (s09)                       |
|----------------|------------------|------------------------------------|
| Tools          | 6                | 9 (+spawn/send/read_inbox)         |
| エージェント数  | 単一             | リーダー + N チームメイト           |
| 永続化         | なし             | config.json + JSONL インボックス    |
| スレッド       | バックグラウンドコマンド | 各スレッドで完全なエージェントループ |
| ライフサイクル  | 使い捨て         | idle -> working -> idle            |
| 通信           | なし             | message + broadcast                |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s09.S09AgentTeams
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Spawn alice (coder) and bob (tester). Have alice send bob a message.`
2. `Broadcast "status update: phase 1 complete" to all teammates`
3. `Check the lead inbox for any messages`
4. `/team` と入力してチーム名簿とステータスを確認する
5. `/inbox` と入力してリーダーのインボックスを手動確認する
