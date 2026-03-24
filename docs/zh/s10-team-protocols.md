# s10: Team Protocols (团队协议)

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12`

> *"队友之间要有统一的沟通规矩"* -- 一个 request-response 模式驱动所有协商。
>
> **Harness 层**: 协议 -- 模型之间的结构化握手。

## 问题

s09 中队友能干活能通信, 但缺少结构化协调:

**关机**: 直接杀线程会留下写了一半的文件和过期的 config.json。需要握手 -- 领导请求, 队友批准 (收尾退出) 或拒绝 (继续干)。

**计划审批**: 领导说 "重构认证模块", 队友立刻开干。高风险变更应该先过审。

两者结构一样: 一方发带唯一 ID 的请求, 另一方引用同一 ID 响应。

## 解决方案

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

## 工作原理

1. 领导生成 request_id, 通过收件箱发起关机请求。

```java
// src/main/java/io/mybatis/learn/s10/ProtocolTracker.java
// Python用字典 + threading.Lock; Java用ConcurrentHashMap天然线程安全
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

2. 队友收到请求后, 用 approve/reject 响应。

```java
// TeammateProtocolTool - 队友用@Tool注解响应关闭请求
@Tool(description = "Respond to a shutdown request")
public String shutdownResponse(
        @ToolParam(description = "The request_id") String requestId,
        @ToolParam(description = "true to approve") boolean approve,
        @ToolParam(description = "Reason for decision") String reason) {
    return tracker.respondToShutdown(name, requestId, approve, reason);
}

// ProtocolTracker - 更新追踪器 + 发送响应消息
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

3. 计划审批遵循完全相同的模式。队友提交计划 (生成 request_id), 领导审查 (引用同一个 request_id)。

```java
// ProtocolTracker - 同样的request_id关联模式，两种用途
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

一个 FSM, 两种用途。同样的 `pending -> approved | rejected` 状态机可以套用到任何请求-响应协议上。

## 相对 s09 的变更

| 组件           | 之前 (s09)       | 之后 (s10)                           |
|----------------|------------------|--------------------------------------|
| Tools          | 9                | 12 (+shutdown_req/resp +plan)        |
| 关机           | 仅自然退出       | 请求-响应握手                        |
| 计划门控       | 无               | 提交/审查与审批                      |
| 关联           | 无               | 每个请求一个 request_id              |
| FSM            | 无               | pending -> approved/rejected         |

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s10.S10TeamProtocols
```

试试这些 prompt (英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Spawn alice as a coder. Then request her shutdown.`
2. `List teammates to see alice's status after shutdown approval`
3. `Spawn bob with a risky refactoring task. Review and reject his plan.`
4. `Spawn charlie, have him submit a plan, then approve it.`
5. 输入 `/team` 监控状态
