# s12: Worktree + Task Isolation (Worktree タスク隔離)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > [ s12 ]`

> *"各自のディレクトリで作業し、互いに干渉しない"* -- タスクは目標を管理、worktree はディレクトリを管理、ID で紐付け。
>
> **Harness 層**: ディレクトリ隔離 -- 決して衝突しない並列実行レーン。

## 問題

s11 までにエージェントはタスクを自律的に確保して完了できるようになった。しかし全タスクが1つの共有ディレクトリで走る。2つのエージェントが同時に異なるモジュールをリファクタリングすると -- A が `Config.java` を編集し、B も `Config.java` を編集し、未コミットの変更が互いに汚染し、どちらもクリーンにロールバックできない。

タスクボードは「何をやるか」を追跡するが「どこでやるか」には関知しない。解決策: 各タスクに独立した git worktree ディレクトリを与え、タスク ID で両者を関連付ける。

## 解決策

```
Control plane (.tasks/)             Execution plane (.worktrees/)
+------------------+                +------------------------+
| task_1.json      |                | auth-refactor/         |
|   status: in_progress  <------>   branch: wt/auth-refactor
|   worktree: "auth-refactor"   |   task_id: 1             |
+------------------+                +------------------------+
| task_2.json      |                | ui-login/              |
|   status: pending    <------>     branch: wt/ui-login
|   worktree: "ui-login"       |   task_id: 2             |
+------------------+                +------------------------+
                                    |
                          index.json (worktree registry)
                          events.jsonl (lifecycle log)

State machines:
  Task:     pending -> in_progress -> completed
  Worktree: absent  -> active      -> removed | kept
```

## 仕組み

1. **タスクを作成する。** まず目標を永続化する。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeTaskManager.java
tasks.create("Implement auth refactor", "");
// -> .tasks/task_1.json  status=pending  worktree=""
```

2. **worktree を作成してタスクに紐付ける。** `task_id` を渡すと、タスクが自動的に `in_progress` に遷移する。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java
worktrees.create("auth-refactor", 1, "HEAD");
// -> git worktree add -b wt/auth-refactor .worktrees/auth-refactor HEAD
// -> index.json gets new entry, task_1.json gets worktree="auth-refactor"
```

紐付けは両側に状態を書き込む:

```java
// src/main/java/io/mybatis/learn/s12/WorktreeTaskManager.java
public String bindWorktree(int taskId, String worktree, String owner) {
    var task = load(taskId);
    task.put("worktree", worktree);
    if (owner != null && !owner.isEmpty()) task.put("owner", owner);
    if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
    task.put("updated_at", System.currentTimeMillis() / 1000.0);
    save(task);
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
}
```

3. **worktree 内でコマンドを実行する。** `cwd` が隔離ディレクトリを指す。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java - run()
boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
ProcessBuilder pb = isWindows
        ? new ProcessBuilder("cmd", "/c", command)
        : new ProcessBuilder("sh", "-c", command);
pb.directory(path.toFile());
pb.redirectErrorStream(true);
Process p = pb.start();
String out = new String(p.getInputStream().readAllBytes()).trim();
boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
```

4. **終了処理。** 2つの選択肢:
   - `worktree_keep(name)` -- ディレクトリを保持する。
   - `worktree_remove(name, complete_task=True)` -- ディレクトリを削除し、紐付けられたタスクを完了し、イベントを発行する。1回の呼び出しで後片付けと完了を処理する。

```java
// src/main/java/io/mybatis/learn/s12/WorktreeManager.java
public String remove(String name, boolean force, boolean completeTask) {
    var wt = findWorktree(name);
    events.emit("worktree.remove.before", ...);
    runGit("worktree", "remove", wt.get("path").toString());
    if (completeTask && wt.get("task_id") != null) {
        int taskId = ((Number) wt.get("task_id")).intValue();
        tasks.update(taskId, "completed", null);
        tasks.unbindWorktree(taskId);
        events.emit("task.completed",
                Map.of("id", taskId, "status", "completed"),
                Map.of("name", name), null);
    }
    // index.json を更新: status -> "removed"
}
```

5. **イベントストリーム。** ライフサイクルの各ステップが `.worktrees/events.jsonl` に記録される:

```json
{
  "event": "worktree.remove.after",
  "task": {"id": 1, "status": "completed"},
  "worktree": {"name": "auth-refactor", "status": "removed"},
  "ts": 1730000000
}
```

イベントタイプ: `worktree.create.before/after/failed`, `worktree.remove.before/after/failed`, `worktree.keep`, `task.completed`。

クラッシュ後も `.tasks/` + `.worktrees/index.json` から状態を再構築できる。会話メモリは揮発性だが、ディスク状態は永続的だ。

## s11 からの変更点

| コンポーネント       | 変更前 (s11)               | 変更後 (s12)                                 |
|--------------------|----------------------------|----------------------------------------------|
| 協調               | タスクボード (owner/status)  | タスクボード + worktree 明示的紐付け          |
| 実行スコープ        | 共有ディレクトリ             | タスクごとの隔離ディレクトリ                  |
| 復旧可能性          | タスクステータスのみ         | タスクステータス + worktree インデックス       |
| 終了処理           | タスク完了                   | タスク完了 + 明示的 keep/remove               |
| ライフサイクル可視性 | ログ内に暗黙的              | `.worktrees/events.jsonl` で明示的イベントストリーム |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s12.S12WorktreeIsolation
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Create tasks for backend auth and frontend login page, then list tasks.`
2. `Create worktree "auth-refactor" for task 1, then bind task 2 to a new worktree "ui-login".`
3. `Run "git status --short" in worktree "auth-refactor".`
4. `Keep worktree "ui-login", then list worktrees and inspect events.`
5. `Remove worktree "auth-refactor" with complete_task=true, then list tasks/worktrees/events.`
