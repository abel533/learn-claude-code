# s08: Background Tasks (バックグラウンドタスク)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > [ s08 ] s09 > s10 > s11 > s12`

> *"遅い操作はバックグラウンドへ、エージェントは次を考え続ける"* -- バックグラウンドスレッドがコマンド実行、完了後に通知を注入。
>
> **Harness 層**: バックグラウンド実行 -- モデルが考え続ける間、Harness が待つ。

## 問題

一部のコマンドは数分かかる: `npm install`、`pytest`、`docker build`。ブロッキングループでは、モデルは待つしかない。ユーザーが「依存関係をインストールして、その間に config ファイルを作って」と言っても、エージェントは1つずつしか処理できない。

## 解決策

```
Main thread                Background thread
+-----------------+        +-----------------+
| agent loop      |        | subprocess runs |
| ...             |        | ...             |
| [LLM call] <---+------- | enqueue(result) |
|  ^drain queue   |        +-----------------+
+-----------------+

Timeline:
Agent --[spawn A]--[spawn B]--[other work]----
             |          |
             v          v
          [A runs]   [B runs]      (parallel)
             |          |
             +-- results injected before next LLM call --+
```

## 仕組み

1. BackgroundManager がスレッドセーフな並行コンテナでタスクを追跡する。Java では `ConcurrentHashMap` と `CopyOnWriteArrayList` を使用し、Python の手動ロックを置き換える。

```java
public class BackgroundManager {
    private static final int TIMEOUT_SECONDS = 300;

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final List<Notification> notificationQueue = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    record TaskInfo(String status, String result, String command) {}
    public record Notification(String taskId, String status, String command, String result) {}
}
```

2. `backgroundRun()` が仮想スレッド (Java 21) に投入し、即座にリターンする。Python の `daemon=True` スレッドに比べ、仮想スレッドはより軽量で JVM がスケジュールする。

```java
@Tool(description = "Run a command in a background thread. Returns task_id immediately without waiting.")
public String backgroundRun(
        @ToolParam(description = "The shell command to run in background") String command) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    tasks.put(taskId, new TaskInfo("running", null, command));

    executor.submit(() -> execute(taskId, command));

    return "Background task " + taskId + " started: "
            + command.substring(0, Math.min(80, command.length()));
}
```

3. サブプロセス完了時に、結果が通知キューに入る。`ProcessBuilder` でコマンドを実行し、タイムアウト制御をサポート。

```java
private void execute(String taskId, String command) {
    String status, output;
    try {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); status = "timeout"; }
        else { status = "completed"; }
    } catch (Exception e) { output = "Error: " + e.getMessage(); status = "error"; }

    tasks.put(taskId, new TaskInfo(status, output, command));
    notificationQueue.add(new Notification(taskId, status, command, output));
}
```

4. 毎回のユーザー入力時に通知キューをドレインし、システムプロンプトに注入する。Spring AI の `ChatClient` が内部ツールループを管理するため、毎回のユーザー入力時にドレイン＋システムプロンプト構築に変更。核心的なコンセプトは同じ: fire and forget。

```java
AgentRunner.interactive("s08", userMessage -> {
    // バックグラウンドタスク通知をドレイン（Python のループ前 drain_notifications に相当）
    var notifs = bgManager.drainNotifications();
    String bgContext = "";
    if (!notifs.isEmpty()) {
        String notifText = notifs.stream()
                .map(n -> "[bg:" + n.taskId() + "] " + n.status() + ": " + n.result())
                .collect(Collectors.joining("\n"));
        bgContext = "\n\n<background-results>\n" + notifText + "\n</background-results>";
    }

    String system = "You are a coding agent. Use backgroundRun for long-running commands."
            + bgContext;

    ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultSystem(system)
            .defaultTools(new BashTool(), new ReadFileTool(),
                    new WriteFileTool(), new EditFileTool(), bgManager)
            .build();

    return chatClient.prompt().user(userMessage).call().content();
});
```

ループはシングルスレッドのまま。サブプロセス I/O だけが並列化される。

## s07 からの変更点

| コンポーネント   | 変更前 (s07)     | 変更後 (s08)                       |
|----------------|------------------|------------------------------------|
| Tools          | 8                | 6 (基本 + backgroundRun + check)   |
| 実行方式       | ブロッキングのみ  | ブロッキング + 仮想スレッド (Java 21) |
| 通知メカニズム  | なし             | 毎ターンドレインの ConcurrentLinkedQueue |
| 並行性         | なし             | 仮想スレッド (より軽量、JVM スケジュール) |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s08.S08BackgroundTasks
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Run "sleep 5 && echo done" in the background, then create a file while it runs`
2. `Start 3 background tasks: "sleep 2", "sleep 4", "sleep 6". Check their status.`
3. `Run pytest in the background and keep working on other things`
