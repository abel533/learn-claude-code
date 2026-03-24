# s10: Team Protocols (チームプロトコル)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12`

> *"チームメイト間には統一の通信ルールが必要"* -- 1つの request-response パターンが全交渉を駆動。
>
> **Harness 層**: プロトコル -- モデル間の構造化されたハンドシェイク。

## 問題

s09 ではチームメイトが作業し通信するが、構造化された協調がない:

**シャットダウン**: スレッドを強制終了するとファイルが中途半端に書かれ、config.json が不正な状態になる。ハンドシェイクが必要 -- リーダーが要求し、チームメイトが承認（完了して退出）か拒否（作業継続）する。

**プラン承認**: リーダーが「認証モジュールをリファクタリングして」と言うと、チームメイトは即座に開始する。リスクの高い変更では、実行前にレビューすべきだ。

両方とも同じ構造: 一方がユニーク ID を持つリクエストを送り、他方がその ID で応答する。

## 解決策

```
Shutdown Protocol            Plan Approval Protocol
==================           ======================

Lead             Teammate    Teammate           Lead
  |                 |           |                 |
  |--shutdown_req-->|           |--plan_req------>|
  | {req_id:"abc"}  |           | {req_id:"xyz"}  |
  |                 |           |                 |
  |<--shutdown_resp-|           |<--plan_resp-----|
  | {req_id:"abc",  |           | {req_id:"xyz",  |
  |  approve:true}  |           |  approve:true}  |

Shared FSM:
  [pending] --approve--> [approved]
  [pending] --reject---> [rejected]

Trackers:
  shutdown_requests = {req_id: {target, status}}
  plan_requests     = {req_id: {from, plan, status}}
```

## 仕組み

1. リーダーが request_id を生成し、インボックス経由でシャットダウンを開始する。

```java
// src/main/java/io/mybatis/learn/s10/ProtocolTracker.java
// Python は辞書 + threading.Lock を使用、Java は ConcurrentHashMap で天然スレッドセーフ
private final ConcurrentHashMap<String, Map<String, String>> shutdownRequests
        = new ConcurrentHashMap<>();

public String handleShutdownRequest(String teammate) {
    String reqId = UUID.randomUUID().toString().substring(0, 8);
    shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
            "target", teammate, "status", "pending")));
    bus.send("lead", teammate, "Please shut down gracefully.",
            "shutdown_request", Map.of("request_id", reqId));
    return "Shutdown request " + reqId + " sent to '" + teammate
            + "' (status: pending)";
}
```

2. チームメイトがリクエストを受信し、承認または拒否で応答する。

```java
// TeammateProtocolTool - チームメイトが @Tool アノテーションでシャットダウン要求に応答
@Tool(description = "Respond to a shutdown request")
public String shutdownResponse(
        @ToolParam(description = "The request_id") String requestId,
        @ToolParam(description = "true to approve") boolean approve,
        @ToolParam(description = "Reason for decision") String reason) {
    return tracker.respondToShutdown(name, requestId, approve, reason);
}

// ProtocolTracker - トラッカー更新 + レスポンスメッセージ送信
public String respondToShutdown(String sender, String requestId,
                                boolean approve, String reason) {
    var req = shutdownRequests.get(requestId);
    if (req != null) {
        req.put("status", approve ? "approved" : "rejected");
    }
    bus.send(sender, "lead", reason != null ? reason : "",
            "shutdown_response",
            Map.of("request_id", requestId, "approve", approve));
    return "Shutdown " + (approve ? "approved" : "rejected");
}
```

3. プラン承認もまったく同じパターン。チームメイトがプランを提出（request_id を生成）、リーダーがレビュー（同じ request_id を参照）。

```java
// ProtocolTracker - 同じ request_id 関連パターン、2つの用途
private final ConcurrentHashMap<String, Map<String, String>> planRequests
        = new ConcurrentHashMap<>();

public String reviewPlan(String requestId, boolean approve, String feedback) {
    var req = planRequests.get(requestId);
    if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";
    req.put("status", approve ? "approved" : "rejected");
    bus.send("lead", req.get("from"), feedback != null ? feedback : "",
            "plan_approval_response",
            Map.of("request_id", requestId, "approve", approve,
                    "feedback", feedback != null ? feedback : ""));
    return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
}
```

1つの FSM、2つの用途。同じ `pending -> approved | rejected` 状態機械が、あらゆるリクエスト-レスポンスプロトコルに適用できる。

## s09 からの変更点

| コンポーネント   | 変更前 (s09)     | 変更後 (s10)                         |
|----------------|------------------|--------------------------------------|
| Tools          | 9                | 12 (+shutdown_req/resp +plan)        |
| シャットダウン  | 自然終了のみ     | リクエスト-レスポンスハンドシェイク    |
| プランゲーティング | なし           | 提出/レビューと承認                   |
| 関連付け       | なし             | リクエストごとに request_id           |
| FSM            | なし             | pending -> approved/rejected         |

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s10.S10TeamProtocols
```

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Spawn alice as a coder. Then request her shutdown.`
2. `List teammates to see alice's status after shutdown approval`
3. `Spawn bob with a risky refactoring task. Review and reject his plan.`
4. `Spawn charlie, have him submit a plan, then approve it.`
5. `/team` と入力してステータスを監視する
