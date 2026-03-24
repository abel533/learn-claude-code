# s11: Autonomous Agents (自律エージェント)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12`

> *"チームメイトが自らボードを見て、仕事を取る"* -- リーダーが逐一割り振る必要はない、自己組織化。
>
> **Harness 層**: 自律 -- 指示なしで仕事を見つけるモデル。

## 問題

s09-s10 では、チームメイトは明示的に指示された時のみ作業する。リーダーは各チームメイトにプロンプトを書き、タスクボード上の10個の未割り当てタスクを手動で割り当てる。これはスケールしない。

真の自律性: チームメイトが自分でタスクボードをスキャンし、未確保のタスクを確保し、完了したら次を探す。

もう1つの問題: コンテキスト圧縮 (s06) 後にエージェントが自分の正体を忘れる可能性がある。アイデンティティ再注入がこれを解決する。

## 解決策

```
Teammate lifecycle with idle cycle:

+-------+
| spawn |
+---+---+
    |
    v
+-------+   tool_use     +-------+
| WORK  | <------------- |  LLM  |
+---+---+                +-------+
    |
    | stop_reason != tool_use (or idle tool called)
    v
+--------+
|  IDLE  |  poll every 5s for up to 60s
+---+----+
    |
    +---> check inbox --> message? ----------> WORK
    |
    +---> scan .tasks/ --> unclaimed? -------> claim -> WORK
    |
    +---> 60s timeout ----------------------> SHUTDOWN

Identity via system prompt (always present):
  ChatClient.builder(chatModel)
      .defaultSystem(identityPrompt)  // 毎回の呼び出しで自動付与
```

## 仕組み

1. チームメイトのループは WORK と IDLE の2フェーズ。LLM がツール呼び出しを止めた時（または `idle` ツールを呼んだ時）、IDLE フェーズに入る。

```java
// src/main/java/io/mybatis/learn/s11/S11AutonomousAgents.java
// AutonomousTeammateManager.autonomousLoop()

private void autonomousLoop(String name, String role, String initialPrompt) {
    // idle フラグ: ツール呼び出し時に設定、外部ループが検出
    AtomicBoolean idleRequested = new AtomicBoolean(false);
    var idleTool = new IdleTool(idleRequested);

    ChatClient client = ChatClient.builder(chatModel)
            .defaultSystem(sysPrompt)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(),
                    messageTool, protocolTool, idleTool, claimTool)
            .build();

    while (true) {
        // -- WORK PHASE --
        String nextMsg = initialPrompt;
        for (int round = 0; round < 50 && nextMsg != null; round++) {
            var inbox = bus.readInbox(name);
            // ... インボックスメッセージを nextMsg にマージ ...
            idleRequested.set(false);
            String response = client.prompt(sb.toString()).call().content();
            if (idleRequested.get()) break;  // idle ツールが呼ばれた
            nextMsg = null;  // 以降のラウンドは inbox 駆動
        }

        // -- IDLE PHASE --
        setStatus(name, "idle");
        // ... インボックス + タスクボードをポーリング（下記参照） ...
        if (!resume) { setStatus(name, "shutdown"); return; }
        setStatus(name, "working");
    }
}
```

2. IDLE フェーズがインボックスとタスクボードをポーリングする。

```java
// IDLE PHASE: インボックス + タスクボードをポーリング
setStatus(name, "idle");
boolean resume = false;
int polls = IDLE_TIMEOUT / Math.max(POLL_INTERVAL, 1);  // 60/5 = 12

for (int p = 0; p < polls; p++) {
    Thread.sleep(POLL_INTERVAL * 1000L);

    // インボックスをチェック
    var inbox = bus.readInbox(name);
    if (!inbox.isEmpty()) {
        initialPrompt = "<inbox>" + mapper.writeValueAsString(inbox) + "</inbox>";
        resume = true;
        break;
    }

    // タスクボードをスキャン
    var unclaimed = scanUnclaimedTasks(tasksDir);
    if (!unclaimed.isEmpty()) {
        var task = unclaimed.get(0);
        int taskId = ((Number) task.get("id")).intValue();
        claimTask(tasksDir, taskId, name);
        initialPrompt = String.format(
                "<auto-claimed>Task #%d: %s\n%s</auto-claimed>",
                taskId, task.get("subject"),
                task.getOrDefault("description", ""));
        resume = true;
        break;
    }
}

if (!resume) { setStatus(name, "shutdown"); return; }
setStatus(name, "working");
```

3. タスクボードスキャン: pending ステータスかつ owner なしかつブロックされていないタスクを探す。

```java
static List<Map<String, Object>> scanUnclaimedTasks(Path tasksDir) {
    if (!Files.exists(tasksDir)) return List.of();
    List<Map<String, Object>> unclaimed = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    try (var files = Files.list(tasksDir)) {
        files.filter(f -> f.getFileName().toString().startsWith("task_")
                       && f.getFileName().toString().endsWith(".json"))
             .sorted()
             .forEach(f -> {
                 Map<String, Object> task = mapper.readValue(f.toFile(), Map.class);
                 if ("pending".equals(task.get("status"))
                     && (task.get("owner") == null || "".equals(task.get("owner")))
                     && (task.get("blockedBy") == null
                         || ((List<?>) task.get("blockedBy")).isEmpty())) {
                     unclaimed.add(task);
                 }
             });
    }
    return unclaimed;
}
```

4. アイデンティティ保持: Java/Spring AI の `ChatClient.defaultSystem()` は毎回の呼び出しで自動的にシステムプロンプトを付与するため、アイデンティティ情報は常に存在する。Python 版のように圧縮後に手動で再注入する必要はない。

```java
// アイデンティティ情報は defaultSystem で構築時に注入、毎回の prompt で自動付与
String sysPrompt = String.format(
        "You are '%s', role: %s, team: %s, at %s. "
        + "Use idle tool when you have no more work. You will auto-claim new tasks.",
        name, role, teamName, workDir);

ChatClient client = ChatClient.builder(chatModel)
        .defaultSystem(sysPrompt)  // アイデンティティは常にシステムプロンプトに存在
        .defaultTools(new BashTool(), new ReadFileTool(),
                new WriteFileTool(), new EditFileTool(),
                messageTool, protocolTool, idleTool, claimTool)
        .build();
```

## s10 からの変更点

| コンポーネント   | 変更前 (s10)     | 変更後 (s11)                     |
|----------------|------------------|----------------------------------|
| Tools          | 12               | 14 (+idle, +claim_task)          |
| 自律性         | リーダー指示     | 自己組織化                       |
| IDLE フェーズ   | なし             | インボックス + タスクボードをポーリング |
| タスク確保     | 手動のみ         | 未割り当てタスクの自動確保        |
| アイデンティティ | システムプロンプト | + 圧縮後の再注入                 |
| タイムアウト    | なし             | 60秒 IDLE → 自動シャットダウン    |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s11.S11AutonomousAgents
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Create 3 tasks on the board, then spawn alice and bob. Watch them auto-claim.`
2. `Spawn a coder teammate and let it find work from the task board itself`
3. `Create tasks with dependencies. Watch teammates respect the blocked order.`
4. `/tasks` と入力して owner 付きのタスクボードを確認する
5. `/team` と入力して誰が作業中でアイドルかを監視する
