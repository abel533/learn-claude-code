package io.mybatis.learn.s10;

import io.mybatis.learn.core.team.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议追踪器 - 关闭协议 + 计划审批协议（请求-响应FSM）
 *
 * TIPS: 对应Python全局变量 shutdown_requests、plan_requests 和 _tracker_lock（s10第82-84行）。
 * Python用字典 + threading.Lock；Java用 ConcurrentHashMap 天然线程安全。
 * 两种协议使用相同的 request_id 关联模式：pending → approved | rejected。
 */
public class ProtocolTracker {
    private static final Logger log = LoggerFactory.getLogger(ProtocolTracker.class);

    // {request_id: {"target": name, "status": "pending|approved|rejected"}}
    private final ConcurrentHashMap<String, Map<String, String>> shutdownRequests = new ConcurrentHashMap<>();
    // {request_id: {"from": name, "plan": text, "status": "pending|approved|rejected"}}
    private final ConcurrentHashMap<String, Map<String, String>> planRequests = new ConcurrentHashMap<>();
    private final MessageBus bus;

    public ProtocolTracker(MessageBus bus) {
        this.bus = bus;
    }

    // ---- 关闭协议（Lead端） ----

    /**
     * Lead发起关闭请求 → 生成request_id → 发送shutdown_request消息
     */
    public String handleShutdownRequest(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "target", teammate, "status", "pending")));
        log.info("发起关闭请求: requestId={}, target={}", reqId, teammate);
        bus.send("lead", teammate, "Please shut down gracefully.",
                "shutdown_request", Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "' (status: pending)";
    }

    /**
     * Lead查询关闭状态
     */
    public String checkShutdownStatus(String requestId) {
        if (log.isDebugEnabled()) {
            System.out.printf("🔍 查询关闭请求状态: %s%n", requestId);
        }
        var req = shutdownRequests.get(requestId);
        return req != null ? req.toString() : "{\"error\": \"not found\"}";
    }

    // ---- 关闭协议（Teammate端） ----

    /**
     * Teammate响应关闭请求 → 更新追踪器 → 发送shutdown_response消息
     */
    public String respondToShutdown(String sender, String requestId, boolean approve, String reason) {
        var req = shutdownRequests.get(requestId);
        if (req != null) {
            req.put("status", approve ? "approved" : "rejected");
        }
        log.info("响应关闭请求: sender={}, requestId={}, approve={}", sender, requestId, approve);
        bus.send(sender, "lead", reason != null ? reason : "",
                "shutdown_response", Map.of("request_id", requestId, "approve", approve));
        return "Shutdown " + (approve ? "approved" : "rejected");
    }

    // ---- 计划审批协议（Teammate端） ----

    /**
     * Teammate提交计划 → 生成request_id → 发送plan_approval_response消息
     */
    public String submitPlan(String sender, String planText) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        planRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "from", sender, "plan", planText, "status", "pending")));
        log.info("提交计划审批: requestId={}, sender={}", reqId, sender);
        bus.send(sender, "lead", planText, "plan_approval_response",
                Map.of("request_id", reqId, "plan", planText));
        return "Plan submitted (request_id=" + reqId + "). Waiting for lead approval.";
    }

    // ---- 计划审批协议（Lead端） ----

    /**
     * Lead审批计划 → 更新追踪器 → 发送plan_approval_response消息
     */
    public String reviewPlan(String requestId, boolean approve, String feedback) {
        var req = planRequests.get(requestId);
        if (req == null) {
            log.warn("审批计划失败，请求不存在: requestId={}", requestId);
            return "Error: Unknown plan request_id '" + requestId + "'";
        }
        req.put("status", approve ? "approved" : "rejected");
        log.info("审批计划: requestId={}, from={}, approve={}", requestId, req.get("from"), approve);
        bus.send("lead", req.get("from"), feedback != null ? feedback : "",
                "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve,
                        "feedback", feedback != null ? feedback : ""));
        return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
    }
}
