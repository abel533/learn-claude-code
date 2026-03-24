package io.mybatis.learn.full;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mybatis.learn.core.AgentRunner;
import io.mybatis.learn.core.team.MessageBus;
import io.mybatis.learn.core.tools.*;
import io.mybatis.learn.s03.TodoManager;
import io.mybatis.learn.s04.SubagentTool;
import io.mybatis.learn.s05.SkillLoader;
import io.mybatis.learn.s07.TaskManager;
import io.mybatis.learn.s08.BackgroundManager;
import io.mybatis.learn.s10.ProtocolTracker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;
import java.util.*;

/**
 * Full Agent - 融合 S01-S11 全部机制的综合型Agent
 *
 * TIPS: 对应Python s_full.py（全部700+行）。
 * 组合了12节课的所有核心概念：
 *
 *   S01: ChatClient基础循环          → AgentRunner.interactive()
 *   S02: 4个基础工具                 → BashTool, ReadFileTool, WriteFileTool, EditFileTool
 *   S03: TodoManager + 催促提醒      → todos.render() 注入系统提示 + nag counter
 *   S04: 子Agent委派                → SubagentTool
 *   S05: 技能加载                    → SkillLoader
 *   S06: 上下文压缩（简化版）         → /compact 命令
 *   S07: 持久化任务板                → TaskManager
 *   S08: 后台任务 + 通知排空         → BackgroundManager.drainNotifications()
 *   S09: 团队消息总线                → MessageBus + FullTeammateManager
 *   S10: 关闭/计划协议               → ProtocolTracker
 *   S11: 自治队友（空闲轮询+自动认领）→ FullTeammateManager idle+autoclaim
 *
 * 每次REPL调用前的流水线：
 *   1. 排空后台通知 → 注入 <background-results>
 *   2. 排空Lead收件箱 → 注入 <inbox>
 *   3. Todo催促检查 → 注入 <reminder>
 *   4. 重建ChatClient（注入当前todo状态到系统提示）
 *   5. 调用LLM
 *
 * REPL命令：/compact, /tasks, /team, /inbox
 * 工具总数：~20个（4基础 + TodoWrite + task + load_skill + 4任务 + 2后台 + 7团队协议）
 */
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class SFullAgent implements CommandLineRunner {

    @Autowired
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        Path workDir = Path.of(System.getProperty("user.dir"));
        Path teamDir = workDir.resolve(".team");

        // ---- 初始化所有组件 ----

        // S03: 内存中的todo列表
        TodoManager todos = new TodoManager();

        // S04: 子Agent委派
        SubagentTool subagent = new SubagentTool(chatModel);

        // S05: 技能加载
        SkillLoader skills = new SkillLoader(workDir.resolve("skills"));

        // S07: 持久化任务板
        TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

        // S08: 后台命令执行
        BackgroundManager bgMgr = new BackgroundManager();

        // S09: 团队消息总线
        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));

        // S10: 协议追踪
        ProtocolTracker protocols = new ProtocolTracker(bus);

        // S09+S10+S11: 自治队友管理
        FullTeammateManager team = new FullTeammateManager(
                chatModel, bus, teamDir, workDir.resolve(".tasks"));

        // Lead专用工具
        LeadTools leadTools = new LeadTools(bus, team, protocols, taskMgr);

        // ---- 系统提示 ----
        String baseSystem = "You are a coding agent at " + workDir + ". Use tools to solve tasks.\n"
                + "Prefer task_create/task_update/task_list for multi-step work. "
                + "Use updateTodos for short checklists.\n"
                + "Use task for subagent delegation. Use load_skill for specialized knowledge.\n"
                + "Skills: " + skills.getDescriptions();

        // S03: 催促计数器
        final int[] roundsWithoutTodo = {0};
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("Full Agent initialized. Components: todo, subagent, skills, "
                + "tasks, background, team, protocols");
        System.out.println("Commands: /compact, /tasks, /team, /inbox, q/exit");

        // ---- REPL ----
        AgentRunner.interactive("s_full", input -> {
            // REPL命令
            if ("/compact".equals(input)) {
                return "[compact] 无状态模式，无会话历史可压缩。"
                        + "每次调用独立进行（TIPS: Python版维护messages列表并在超过100K token时自动压缩）。";
            }
            if ("/tasks".equals(input)) return taskMgr.taskList();
            if ("/team".equals(input)) return team.listAll();
            if ("/inbox".equals(input)) {
                try { return mapper.writeValueAsString(bus.readInbox("lead")); }
                catch (Exception e) { return "[]"; }
            }

            // ---- 调用前流水线 ----
            StringBuilder prefix = new StringBuilder();

            // S08: 排空后台通知
            var notifications = bgMgr.drainNotifications();
            if (!notifications.isEmpty()) {
                prefix.append("<background-results>\n");
                for (var n : notifications) {
                    prefix.append(String.format("[bg:%s] %s: %s%n",
                            n.taskId(), n.status(),
                            AgentRunner.truncate(n.result() != null ? n.result() : "", 500)));
                }
                prefix.append("</background-results>\n");
            }

            // S09: 排空Lead收件箱
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try {
                    prefix.append("<inbox>")
                            .append(mapper.writeValueAsString(inbox))
                            .append("</inbox>\n");
                } catch (Exception e) { /* ignore */ }
            }

            // S03: Todo催促（连续3轮未更新todo时提醒）
            String rendered = todos.render();
            boolean hasOpenTodos = rendered.contains("[ ]") || rendered.contains("[>");
            if (hasOpenTodos && roundsWithoutTodo[0] >= 3) {
                prefix.append("<reminder>You have open todos. Please update them.</reminder>\n");
            }
            roundsWithoutTodo[0]++;

            String fullInput = prefix.length() > 0 ? prefix + "\n" + input : input;

            // 每次重建ChatClient，注入最新todo状态到系统提示
            String currentSystem = baseSystem + "\n\nCurrent todos:\n" + rendered;

            ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem(currentSystem)
                    .defaultTools(
                            new BashTool(), new ReadFileTool(),
                            new WriteFileTool(), new EditFileTool(),
                            todos, subagent, skills,
                            taskMgr, bgMgr, leadTools)
                    .build();

            String response = client.prompt(fullInput).call().content();

            // 简易启发式：如果响应提及"todo"，重置催促计数器
            if (response != null && response.toLowerCase().contains("todo")) {
                roundsWithoutTodo[0] = 0;
            }

            return response;
        });
    }

    // ---- Lead专用工具集：团队管理 + 协议 + 任务认领 ----

    /**
     * TIPS: 对应Python s_full.py中Lead可用的团队/协议工具。
     * 7个工具：spawn_teammate, list_teammates, send_message, read_inbox, broadcast,
     *         shutdown_request, plan_approval
     *
     * Python中这些是TOOL_HANDLERS字典里的独立函数；
     * Java中聚合为一个@Tool对象，通过ChatClient.defaultTools()注册。
     */
    public static class LeadTools {
        private final MessageBus bus;
        private final FullTeammateManager team;
        private final ProtocolTracker protocols;
        private final TaskManager taskMgr;

        public LeadTools(MessageBus bus, FullTeammateManager team,
                         ProtocolTracker protocols, TaskManager taskMgr) {
            this.bus = bus;
            this.team = team;
            this.protocols = protocols;
            this.taskMgr = taskMgr;
        }

        @Tool(description = "Spawn an autonomous teammate that runs in its own thread with idle+auto-claim")
        public String spawnTeammate(
                @ToolParam(description = "Unique teammate name") String name,
                @ToolParam(description = "Role description") String role,
                @ToolParam(description = "Initial task prompt") String prompt) {
            return team.spawn(name, role, prompt);
        }

        @Tool(description = "List all teammates with name, role, status")
        public String listTeammates() {
            return team.listAll();
        }

        @Tool(description = "Send message to a teammate's inbox")
        public String sendMessage(
                @ToolParam(description = "Recipient name") String to,
                @ToolParam(description = "Message content") String content) {
            return bus.send("lead", to, content);
        }

        @Tool(description = "Read and drain the lead's inbox")
        public String readInbox() {
            try { return new ObjectMapper().writeValueAsString(bus.readInbox("lead")); }
            catch (Exception e) { return "[]"; }
        }

        @Tool(description = "Broadcast message to all teammates")
        public String broadcast(
                @ToolParam(description = "Message content") String content) {
            return bus.broadcast("lead", content, team.memberNames());
        }

        @Tool(description = "Request a teammate to shutdown gracefully")
        public String shutdownRequest(
                @ToolParam(description = "Teammate name to shutdown") String teammate) {
            return protocols.handleShutdownRequest(teammate);
        }

        @Tool(description = "Approve or reject a teammate's plan submission")
        public String planApproval(
                @ToolParam(description = "Plan request ID") String requestId,
                @ToolParam(description = "Teammate who submitted the plan") String teammate,
                @ToolParam(description = "Whether to approve the plan") boolean approve,
                @ToolParam(description = "Feedback message") String feedback) {
            String result = protocols.reviewPlan(requestId, approve, feedback);
            String status = approve ? "approved" : "rejected";
            bus.send("lead", teammate, feedback != null ? feedback : status,
                    "plan_approval_response",
                    Map.of("request_id", requestId, "status", status));
            return result;
        }

        @Tool(description = "Claim an unclaimed task for the lead agent")
        public String claimTask(
                @ToolParam(description = "Task ID to claim") int taskId) {
            return taskMgr.taskUpdate(taskId, "in_progress", null, null);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(SFullAgent.class, args);
    }
}
