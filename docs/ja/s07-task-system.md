# s07: Task System (タスクシステム)

`s01 > s02 > s03 > s04 > s05 > s06 | [ s07 ] s08 > s09 > s10 > s11 > s12`

> *"大きな目標を小タスクに分解し、順序付けし、ディスクに記録する"* -- ファイルベースのタスクグラフ、マルチエージェント協調の基盤。
>
> **Harness 層**: 永続タスク -- どの会話よりも長く生きる目標。

## 問題

s03 の TodoManager はメモリ上のフラットなチェックリストに過ぎない: 順序なし、依存関係なし、ステータスは完了か未完了のみ。実際の目標には構造がある -- タスク B はタスク A に依存し、タスク C と D は並行実行でき、タスク E は C と D の両方を待つ。

明示的な関係がなければ、エージェントは何が実行可能で、何がブロックされ、何が同時に走れるかを判断できない。しかもリストはメモリ上にしかないため、コンテキスト圧縮 (s06) で消える。

## 解決策

フラットなチェックリストをディスクに永続化する**タスクグラフ**に昇格させる。各タスクは1つの JSON ファイルで、ステータス・前方依存 (`blockedBy`)・後方依存 (`blocks`) を持つ。タスクグラフは常に3つの問いに答える:

- **何が実行可能か?** -- `pending` ステータスで `blockedBy` が空のタスク。
- **何がブロックされているか?** -- 未完了の依存を待つタスク。
- **何が完了したか?** -- `completed` のタスク。完了時に後続タスクを自動的にアンブロックする。

```
.tasks/
  task_1.json  {"id":1, "status":"completed"}
  task_2.json  {"id":2, "blockedBy":[1], "status":"pending"}
  task_3.json  {"id":3, "blockedBy":[1], "status":"pending"}
  task_4.json  {"id":4, "blockedBy":[2,3], "status":"pending"}

タスクグラフ (DAG):
                 +----------+
            +--> | task 2   | --+
            |    | pending  |   |
+----------+     +----------+    +--> +----------+
| task 1   |                          | task 4   |
| completed| --> +----------+    +--> | blocked  |
+----------+     | task 3   | --+     +----------+
                 | pending  |
                 +----------+

順序:       task 1 は 2 と 3 より先に完了する必要がある
並行:       task 2 と 3 は同時に実行できる
依存:       task 4 は 2 と 3 の両方を待つ
ステータス: pending -> in_progress -> completed
```

このタスクグラフは s07 以降の全メカニズムの協調バックボーンとなる: バックグラウンド実行 (s08)、マルチエージェントチーム (s09+)、worktree 分離 (s12) はすべてこの同じ構造を読み書きする。

## 仕組み

1. **TaskManager**: タスクごとに1つの JSON ファイル、依存グラフ付き CRUD。Jackson `ObjectMapper` で JSON シリアライゼーションを行う。

```java
public class TaskManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path dir;
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        Files.createDirectories(dir);
        this.nextId = maxId() + 1;
    }

    @Tool(description = "Create a new task with subject and optional description")
    public String taskCreate(
            @ToolParam(description = "Short subject of the task") String subject,
            @ToolParam(description = "Detailed description", required = false) String description) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("status", "pending");
        task.put("blockedBy", new ArrayList<>());
        task.put("blocks", new ArrayList<>());
        save(task);
        nextId++;
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
    }
}
```

2. **依存解除**: タスク完了時に、他タスクの `blockedBy` リストから完了 ID を除去し、後続タスクをアンブロックする。

```java
private void clearDependency(int completedId) {
    try (Stream<Path> files = Files.list(dir)) {
        files.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                .forEach(f -> {
                    Map<String, Object> task = MAPPER.readValue(
                            Files.readString(f), new TypeReference<>() {});
                    List<Integer> blockedBy = (List<Integer>) task.get("blockedBy");
                    if (blockedBy != null && blockedBy.remove(Integer.valueOf(completedId))) {
                        save(task);
                    }
                });
    }
}
```

3. **ステータス遷移 + 依存配線**: `taskUpdate` がステータス変更と依存エッジを担う。status が `completed` になると自動的に `clearDependency` を呼び出す。`blockedBy`/`blocks` は双方向の関係。

```java
@Tool(description = "Update a task's status or dependencies.")
public String taskUpdate(
        @ToolParam(description = "Task ID") int taskId,
        @ToolParam(description = "New status", required = false) String status,
        @ToolParam(description = "Task IDs that block this task", required = false) List<Integer> addBlockedBy,
        @ToolParam(description = "Task IDs that this task blocks", required = false) List<Integer> addBlocks) {
    Map<String, Object> task = load(taskId);
    if (status != null) {
        task.put("status", status);
        if ("completed".equals(status)) {
            clearDependency(taskId);
        }
    }
    // addBlockedBy / addBlocks の双方向依存を処理 ...
    save(task);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
}
```

4. **Spring AI 自動ツール登録**: `TaskManager` を `defaultTools` として `ChatClient` に渡すと、Spring AI が `@Tool` アノテーションメソッドを自動認識する。手動 dispatch map は不要。

```java
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S07TaskSystem implements CommandLineRunner {

    private final ChatClient chatClient;

    public S07TaskSystem(ChatModel chatModel) {
        Path tasksDir = Path.of(System.getProperty("user.dir"), ".tasks");
        TaskManager taskManager = new TaskManager(tasksDir);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent. Use task tools to plan and track work.")
                .defaultTools(
                        new BashTool(), new ReadFileTool(),
                        new WriteFileTool(), new EditFileTool(),
                        taskManager   // TaskManager 内の @Tool メソッドが自動登録
                )
                .build();
    }
}
```

s07 以降、タスクグラフがマルチステップ作業のデフォルト。s03 の Todo は軽量な単一セッション用チェックリストとして残る。

## s06 からの変更点

| コンポーネント | 変更前 (s06) | 変更後 (s07) |
|---|---|---|
| Tools | 5 | 8 (`task_create/update/list/get`) |
| 計画モデル | フラットチェックリスト (メモリのみ) | 依存関係付きタスクグラフ (ディスク) |
| 関係 | なし | `blockedBy` + `blocks` エッジ |
| ステータス追跡 | 完了か未完了 | `pending` -> `in_progress` -> `completed` |
| 永続性 | 圧縮で消失 | 圧縮・再起動後も存続 |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s07.S07TaskSystem
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Create 3 tasks: "Setup project", "Write code", "Write tests". Make them depend on each other in order.`
2. `List all tasks and show the dependency graph`
3. `Complete task 1 and then list tasks to see task 2 unblocked`
4. `Create a task board for refactoring: parse -> transform -> emit -> test, where transform and emit can run in parallel after parse`
