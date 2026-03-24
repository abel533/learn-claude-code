# s10: Team Protocols

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12`

> *"Teammates need shared communication rules"* -- one request-response pattern drives all negotiation.
>
> **Harness layer**: Protocols -- structured handshakes between models.

## Problem

In s09, teammates work and communicate but lack structured coordination:

**Shutdown**: Killing a thread leaves files half-written and config.json stale. You need a handshake -- the lead requests, the teammate approves (finish and exit) or rejects (keep working).

**Plan approval**: When the lead says "refactor the auth module," the teammate starts immediately. For high-risk changes, the lead should review the plan first.

Both share the same structure: one side sends a request with a unique ID, the other responds referencing that ID.

## Solution

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

## How It Works

1. The lead initiates shutdown by generating a request_id and sending through the inbox.

```java
// src/main/java/io/mybatis/learn/s10/ProtocolTracker.java
// Python uses dict + threading.Lock; Java uses ConcurrentHashMap for natural thread safety
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

2. The teammate receives the request and responds with approve/reject.

```java
// TeammateProtocolTool - teammates respond to shutdown requests via @Tool annotation
@Tool(description = "Respond to a shutdown request")
public String shutdownResponse(
        @ToolParam(description = "The request_id") String requestId,
        @ToolParam(description = "true to approve") boolean approve,
        @ToolParam(description = "Reason for decision") String reason) {
    return tracker.respondToShutdown(name, requestId, approve, reason);
}

// ProtocolTracker - updates tracker + sends response message
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

3. Plan approval follows the identical pattern. The teammate submits a plan (generating a request_id), the lead reviews (referencing the same request_id).

```java
// ProtocolTracker - same request_id correlation pattern, two use cases
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

One FSM, two applications. The same `pending -> approved | rejected` state machine handles any request-response protocol.

## What Changed From s09

| Component      | Before (s09)     | After (s10)                          |
|----------------|------------------|--------------------------------------|
| Tools          | 9                | 12 (+shutdown_req/resp +plan)        |
| Shutdown       | Natural exit only| Request-response handshake           |
| Plan gating    | None             | Submit/review with approval          |
| Correlation    | None             | request_id per request               |
| FSM            | None             | pending -> approved/rejected         |

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s10.S10TeamProtocols
```

Try these prompts (English prompts work better with LLMs, but Chinese also works):

1. `Spawn alice as a coder. Then request her shutdown.`
2. `List teammates to see alice's status after shutdown approval`
3. `Spawn bob with a risky refactoring task. Review and reject his plan.`
4. `Spawn charlie, have him submit a plan, then approve it.`
5. Type `/team` to monitor statuses
