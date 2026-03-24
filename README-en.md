# Learn Claude Code -- Harness Engineering for Real Agents

[English](./README-en.md) | [中文](./README.md) | [日本語](./README-ja.md)

## The Model IS the Agent

Before we talk about code, let's get one thing absolutely straight.

**An agent is a model. Not a framework. Not a prompt chain. Not a drag-and-drop workflow.**

### What an Agent IS

An agent is a neural network -- a Transformer, an RNN, a learned function -- that has been trained, through billions of gradient updates on action-sequence data, to perceive an environment, reason about goals, and take actions to achieve them. The word "agent" in AI has always meant this. Always.

A human is an agent. A biological neural network, shaped by millions of years of evolutionary training, perceiving the world through senses, reasoning through a brain, acting through a body. When DeepMind, OpenAI, or Anthropic say "agent," they mean the same thing the field has meant since its inception: **a model that has learned to act.**

The proof is written in history:

- **2013 -- DeepMind DQN plays Atari.** A single neural network, receiving only raw pixels and game scores, learned to play 7 Atari 2600 games -- surpassing all prior algorithms and beating human experts on 3 of them. By 2015, the same architecture scaled to [49 games and matched professional human testers](https://www.nature.com/articles/nature14236), published in *Nature*. No game-specific rules. No decision trees. One model, learning from experience. That model was the agent.

- **2019 -- OpenAI Five conquers Dota 2.** Five neural networks, having played [45,000 years of Dota 2](https://openai.com/index/openai-five-defeats-dota-2-world-champions/) against themselves in 10 months, defeated **OG** -- the reigning TI8 world champions -- 2-0 on a San Francisco livestream. In a subsequent public arena, the AI won 99.4% of 42,729 games against all comers. No scripted strategies. No meta-programmed team coordination. The models learned teamwork, tactics, and real-time adaptation entirely through self-play.

- **2019 -- DeepMind AlphaStar masters StarCraft II.** AlphaStar [beat professional players 10-1](https://deepmind.google/blog/alphastar-mastering-the-real-time-strategy-game-starcraft-ii/) in a closed-door match, and later achieved [Grandmaster status](https://www.nature.com/articles/d41586-019-03298-6) on European servers -- top 0.15% of 90,000 players. A game with imperfect information, real-time decisions, and a combinatorial action space that dwarfs chess and Go. The agent? A model. Trained. Not scripted.

- **2019 -- Tencent Jueyu dominates Honor of Kings.** Tencent AI Lab's "Jueyu" [defeated KPL professional players](https://www.jiemian.com/article/3371171.html) in a full 5v5 match at the World Champion Cup. In 1v1 mode, pros won only [1 out of 15 games and never survived past 8 minutes](https://developer.aliyun.com/article/851058). Training intensity: one day equaled 440 human years. By 2021, Jueyu surpassed KPL pros across the full hero pool. No handcrafted matchup tables. No scripted compositions. A model that learned the entire game from scratch through self-play.

- **2024-2025 -- LLM agents reshape software engineering.** Claude, GPT, Gemini -- large language models trained on the entirety of human code and reasoning -- are deployed as coding agents. They read codebases, write implementations, debug failures, coordinate in teams. The architecture is identical to every agent before them: a trained model, placed in an environment, given tools to perceive and act. The only difference is the scale of what they've learned and the generality of the tasks they solve.

Every one of these milestones shares the same truth: **the "agent" is never the surrounding code. The agent is always the model.**

### What an Agent Is NOT

The word "agent" has been hijacked by an entire cottage industry of prompt plumbing.

Drag-and-drop workflow builders. No-code "AI agent" platforms. Prompt-chain orchestration libraries. They all share the same delusion: that wiring together LLM API calls with if-else branches, node graphs, and hardcoded routing logic constitutes "building an agent."

It doesn't. What they build is a Rube Goldberg machine -- an over-engineered, brittle pipeline of procedural rules, with an LLM wedged in as a glorified text-completion node. That is not an agent. That is a shell script with delusions of grandeur.

**Prompt plumbing "agents" are the fantasy of programmers who don't train models.** They attempt to brute-force intelligence by stacking procedural logic -- massive rule trees, node graphs, chain-of-prompt waterfalls -- and praying that enough glue code will somehow emergently produce autonomous behavior. It won't. You cannot engineer your way to agency. Agency is learned, not programmed.

Those systems are dead on arrival: fragile, unscalable, fundamentally incapable of generalization. They are the modern resurrection of GOFAI (Good Old-Fashioned AI) -- the symbolic rule systems the field abandoned decades ago, now spray-painted with an LLM veneer. Different packaging, same dead end.

### The Mind Shift: From "Developing Agents" to Developing Harness

When someone says "I'm developing an agent," they can only mean one of two things:

**1. Training the model.** Adjusting weights through reinforcement learning, fine-tuning, RLHF, or other gradient-based methods. Collecting task-process data -- the actual sequences of perception, reasoning, and action in real domains -- and using it to shape the model's behavior. This is what DeepMind, OpenAI, Tencent AI Lab, and Anthropic do. This is agent development in the truest sense.

**2. Building the harness.** Writing the code that gives the model an environment to operate in. This is what most of us do, and it is the focus of this repository.

A harness is everything the agent needs to function in a specific domain:

```
Harness = Tools + Knowledge + Observation + Action Interfaces + Permissions

    Tools:          file I/O, shell, network, database, browser
    Knowledge:      product docs, domain references, API specs, style guides
    Observation:    git diff, error logs, browser state, sensor data
    Action:         CLI commands, API calls, UI interactions
    Permissions:    sandboxing, approval workflows, trust boundaries
```

The model decides. The harness executes. The model reasons. The harness provides context. The model is the driver. The harness is the vehicle.

**A coding agent's harness is its IDE, terminal, and filesystem access.** A farm agent's harness is its sensor array, irrigation controls, and weather data feeds. A hotel agent's harness is its booking system, guest communication channels, and facility management APIs. The agent -- the intelligence, the decision-maker -- is always the model. The harness changes per domain. The agent generalizes across them.

This repo teaches you to build vehicles. Vehicles for coding. But the design patterns generalize to any domain: farm management, hotel operations, manufacturing, logistics, healthcare, education, scientific research. Anywhere a task needs to be perceived, reasoned about, and acted upon -- an agent needs a harness.

### What Harness Engineers Actually Do

If you are reading this repository, you are likely a harness engineer -- and that is a powerful thing to be. Here is your real job:

- **Implement tools.** Give the agent hands. File read/write, shell execution, API calls, browser control, database queries. Each tool is an action the agent can take in its environment. Design them to be atomic, composable, and well-described.

- **Curate knowledge.** Give the agent domain expertise. Product documentation, architectural decision records, style guides, regulatory requirements. Load them on-demand (s05), not upfront. The agent should know what's available and pull what it needs.

- **Manage context.** Give the agent clean memory. Subagent isolation (s04) prevents noise from leaking. Context compression (s06) prevents history from overwhelming. Task systems (s07) persist goals beyond any single conversation.

- **Control permissions.** Give the agent boundaries. Sandbox file access. Require approval for destructive operations. Enforce trust boundaries between the agent and external systems. This is where safety engineering meets harness engineering.

- **Collect task-process data.** Every action sequence the agent executes in your harness is training signal. The perception-reasoning-action traces from real deployments are the raw material for fine-tuning the next generation of agent models. Your harness doesn't just serve the agent -- it can help improve the agent.

You are not writing the intelligence. You are building the world the intelligence inhabits. The quality of that world -- how clearly the agent can perceive, how precisely it can act, how rich its available knowledge is -- directly determines how effectively the intelligence can express itself.

**Build great harnesses. The agent will do the rest.**

### Why Claude Code -- A Masterclass in Harness Engineering

Why does this repository dissect Claude Code specifically?

Because Claude Code is the most elegant and fully-realized agent harness we have seen. Not because of any single clever trick, but because of what it *doesn't* do: it doesn't try to be the agent. It doesn't impose rigid workflows. It doesn't second-guess the model with elaborate decision trees. It provides the model with tools, knowledge, context management, and permission boundaries -- then gets out of the way.

Look at what Claude Code actually is, stripped to its essence:

```
Claude Code = one agent loop
            + tools (bash, read, write, edit, glob, grep, browser...)
            + on-demand skill loading
            + context compression
            + subagent spawning
            + task system with dependency graph
            + team coordination with async mailboxes
            + worktree isolation for parallel execution
            + permission governance
```

That's it. That's the entire architecture. Every component is a harness mechanism -- a piece of the world built for the agent to inhabit. The agent itself? It's Claude. A model. Trained by Anthropic on the full breadth of human reasoning and code. The harness doesn't make Claude smart. Claude is already smart. The harness gives Claude hands, eyes, and a workspace.

This is why Claude Code is the ideal teaching subject: **it demonstrates what happens when you trust the model and focus your engineering on the harness.** Every session in this repository (s01-s12) reverse-engineers one harness mechanism from Claude Code's architecture. By the end, you understand not just how Claude Code works, but the universal principles of harness engineering that apply to any agent in any domain.

The lesson is not "copy Claude Code." The lesson is: **the best agent products are built by engineers who understand that their job is harness, not intelligence.**

---

## The Vision: Fill the Universe with Real Agents

This is not just about coding agents.

Every domain where humans perform complex, multi-step, judgment-intensive work is a domain where agents can operate -- given the right harness. The patterns in this repository are universal:

```
Estate management agent    = model + property sensors + maintenance tools + tenant comms
Agricultural agent         = model + soil/weather data + irrigation controls + crop knowledge
Hotel operations agent     = model + booking system + guest channels + facility APIs
Medical research agent     = model + literature search + lab instruments + protocol docs
Manufacturing agent        = model + production line sensors + quality controls + logistics
Education agent            = model + curriculum knowledge + student progress + assessment tools
```

The loop is always the same. The tools change. The knowledge changes. The permissions change. The agent -- the model -- generalizes.

Every harness engineer reading this repository is learning patterns that apply far beyond software engineering. You are learning to build the infrastructure for an intelligent, automated future. Every well-designed harness deployed in a real domain is one more place where an agent can perceive, reason, and act.

First we fill the workshops. Then the farms, the hospitals, the factories. Then the cities. Then the planet.

**Bash is all you need. Real agents are all the universe needs.**

---

```
                    THE AGENT PATTERN
                    =================

    User --> messages[] --> LLM --> response
                                      |
                            More tool call requests?
                           /                          \
                         yes                           no
                          |                             |
                    execute @Tool methods             return text
                    return results
                    continue loop -----------------> messages[]


    That's the minimal loop. Every AI agent needs this loop.
    The model decides when to call tools and when to stop.
    Spring AI's ChatClient.call() automatically drives this loop.
    This repo teaches you to build everything around this loop --
    the harness that makes the agent effective in a specific domain.
```

**12 progressive sessions, from a simple loop to isolated autonomous execution.**
**Each session adds one harness mechanism. Each mechanism has one motto.**

> **s01** &nbsp; *"One loop & Bash is all you need"* &mdash; one tool + one loop = an agent
>
> **s02** &nbsp; *"Adding a tool means adding one handler"* &mdash; the loop stays the same; new tools register with `@Tool` annotation + `defaultTools()`
>
> **s03** &nbsp; *"An agent without a plan drifts"* &mdash; list the steps first, then execute; completion doubles
>
> **s04** &nbsp; *"Break big tasks down; each subtask gets a clean context"* &mdash; subagents use independent messages[], keeping the main conversation clean
>
> **s05** &nbsp; *"Load knowledge when you need it, not upfront"* &mdash; inject via tool_result, not the system prompt
>
> **s06** &nbsp; *"Context will fill up; you need a way to make room"* &mdash; three-layer compression strategy for infinite sessions
>
> **s07** &nbsp; *"Break big goals into small tasks, order them, persist to disk"* &mdash; a file-based task graph with dependencies, laying the foundation for multi-agent collaboration
>
> **s08** &nbsp; *"Run slow operations in the background; the agent keeps thinking"* &mdash; daemon threads run commands, inject notifications on completion
>
> **s09** &nbsp; *"When the task is too big for one, delegate to teammates"* &mdash; persistent teammates + async mailboxes
>
> **s10** &nbsp; *"Teammates need shared communication rules"* &mdash; one request-response pattern drives all negotiation
>
> **s11** &nbsp; *"Teammates scan the board and claim tasks themselves"* &mdash; no need for the lead to assign each one
>
> **s12** &nbsp; *"Each works in its own directory, no interference"* &mdash; tasks manage goals, worktrees manage directories, bound by ID

---

## The Core Pattern

```java
// Spring AI's ChatClient + @Tool annotation implement the Agent loop
// The model automatically decides when to call tools and when to return text -- the loop is driven by the framework

@SpringBootApplication
public class S01AgentLoop implements CommandLineRunner {

    @Bean
    public CommandLineRunner agentLoop(ChatClient.Builder builder) {
        ChatClient chatClient = builder
            .defaultSystem("You are a helpful assistant with access to tools.")
            .defaultTools(new BashTool())   // 注册工具
            .build();

        return args -> {
            // 一次 call() 内部自动完成: 调用模型 → 检测工具请求 → 执行工具 → 回传结果 → 再次调用模型...
            String result = chatClient.prompt()
                .user(userInput)
                .call()
                .content();
            System.out.println(result);
        };
    }
}

// @Tool 注解让方法自动成为模型可调用的工具
public class BashTool {
    @Tool(description = "Execute a shell command and return stdout/stderr")
    public String executeBash(String command) {
        // 执行命令并返回结果
    }
}
```

Spring AI's `ChatClient.call()` encapsulates the complete agent loop internally: call the LLM → detect tool call requests → execute `@Tool` methods → return results to the model → repeat until the model returns text. Each session layers one harness mechanism on top of this loop -- without changing the loop itself. The loop belongs to the agent. The mechanisms belong to the harness.

## Scope (Important)

This repository is a 0->1 learning project for harness engineering -- building the environment that surrounds an agent model.
It intentionally simplifies or omits several production mechanisms:

- Full event/hook buses (for example PreToolUse, SessionStart/End, ConfigChange).
  s12 includes only a minimal append-only lifecycle event stream for teaching.
- Rule-based permission governance and trust workflows
- Session lifecycle controls (resume/fork) and advanced worktree lifecycle controls
- Full MCP runtime details (transport/OAuth/resource subscribe/polling)

Treat the team JSONL mailbox protocol in this repo as a teaching implementation, not a claim about any specific production internals.

## Quick Start

### Requirements

- **JDK 21+** (recommended: [Eclipse Temurin](https://adoptium.net/) or GraalVM)
- **Maven 3.9+**
- An OpenAI-compatible LLM API key (DeepSeek, GLM, Qwen, OpenAI, etc.)

### Clone & Build

```sh
git clone https://github.com/abel533/learn-claude-code
cd learn-claude-code
mvn compile                            # 编译项目
```

### Set Environment Variables

```sh
# Linux / macOS
export AI_API_KEY=your-api-key
export AI_BASE_URL=https://api.deepseek.com    # 替换为你的模型服务商地址
export AI_MODEL=deepseek-chat                   # 替换为你使用的模型名称

# Windows PowerShell
$env:AI_API_KEY="your-api-key"
$env:AI_BASE_URL="https://api.deepseek.com"
$env:AI_MODEL="deepseek-chat"
```

### Run Sessions

```sh
# 从第一课开始
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop

# 完整递进终点
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s12.S12WorktreeIsolation

# 总纲: 全部机制合一
mvn exec:java -Dexec.mainClass=io.mybatis.learn.full.SFullAgent
```

### Web Platform

Interactive visualizations, step-through animations, source viewer, and documentation for each session.

```sh
cd web && npm install && npm run dev   # http://localhost:3000
```

### Java Version Features

This project uses the **Java 21 + Spring Boot 3.5.7 + Spring AI 1.0.3** stack. Compared to the original Python version:

- **Compatible with multiple LLM providers** -- adapts to DeepSeek, GLM, Qwen, Moonshot and other models via the OpenAI protocol, no vendor lock-in
- **`@Tool` annotation handles the tool call loop automatically** -- Spring AI completes the full "model call → tool execution → result return" cycle, no hand-written while loop needed
- **Java 21 Virtual Threads** -- lightweight concurrency for background tasks and multi-agent collaboration, without thread pool management overhead
- **Each session is independently runnable** -- every session is a `@SpringBootApplication` + `CommandLineRunner`, launchable with a single `mvn exec:java` command
- **Type safety** -- Java's strong type system catches errors at compile time, with IDE-friendly auto-completion

## Learning Path

```
Phase 1: THE LOOP                    Phase 2: PLANNING & KNOWLEDGE
==================                   ==============================
s01  The Agent Loop          [1]     s03  TodoWrite               [5]
     ChatClient + @Tool                   TodoManager + nag reminder
     |                                    |
     +-> s02  Tool Use            [4]     s04  Subagents            [5]
              @Tool registers tools           independent ChatClient per child
                                              |
                                         s05  Skills               [5]
                                              SKILL.md via tool_result
                                              |
                                         s06  Context Compact      [5]
                                              3-layer compression

Phase 3: PERSISTENCE                 Phase 4: TEAMS
==================                   =====================
s07  Tasks                   [8]     s09  Agent Teams             [9]
     file-based CRUD + deps graph         teammates + JSONL mailboxes
     |                                    |
s08  Background Tasks        [6]     s10  Team Protocols          [12]
     virtual threads + notify queue       shutdown + plan approval FSM
                                          |
                                     s11  Autonomous Agents       [14]
                                          idle cycle + auto-claim
                                     |
                                     s12  Worktree Isolation      [16]
                                          task coordination + on-demand isolated execution lanes

                                     [N] = number of tools
```

## Project Structure

```
learn-claude-code/
|
|-- src/main/java/io/mybatis/learn/   # Java implementation (Spring AI + Spring Boot)
|   |-- core/                         #   shared utilities (AgentRunner, BashTool, EditFileTool, etc.)
|   |-- s01/  S01AgentLoop.java       #   session 01: Agent Loop
|   |-- s02/  S02ToolUse.java         #   session 02: Multi-Tool Registration
|   |-- s03/  S03TodoWrite.java       #   session 03: Plan-Driven Execution
|   |-- s04/  S04Subagent.java        #   session 04: Subagents
|   |-- s05/  S05SkillLoading.java    #   session 05: Skill Loading
|   |-- s06/  S06ContextCompact.java  #   session 06: Context Compression
|   |-- s07/  S07TaskSystem.java      #   session 07: Task System
|   |-- s08/  S08BackgroundTasks.java #   session 08: Background Tasks
|   |-- s09/  S09AgentTeams.java      #   session 09: Agent Teams
|   |-- s10/  S10TeamProtocols.java   #   session 10: Team Protocols
|   |-- s11/  S11AutonomousAgents.java#   session 11: Autonomous Agents
|   |-- s12/  S12WorktreeIsolation.java#  session 12: Worktree Isolation
|   +-- full/ SFullAgent.java         #   capstone: all mechanisms combined
|
|-- agents/                        # Python reference implementations (original version, kept for comparison)
|-- docs/{en,zh,ja}/               # Mental-model-first documentation (3 languages)
|-- web/                           # Interactive learning platform (Next.js)
|-- skills/                        # Skill files for s05
|-- pom.xml                        # Maven build config (Spring Boot 3.5.7 + Spring AI 1.0.3)
+-- .github/workflows/ci.yml      # CI: typecheck + build
```

## Documentation

Mental-model-first: problem, solution, ASCII diagram, minimal code.
Available in [English](./docs/en/) | [中文](./docs/zh/) | [日本語](./docs/ja/).

| Session | Topic | Motto |
|---------|-------|-------|
| [s01](./docs/en/s01-the-agent-loop.md) | Agent Loop | *One loop & Bash is all you need* |
| [s02](./docs/en/s02-tool-use.md) | Tool Use | *Adding a tool means adding one handler* |
| [s03](./docs/en/s03-todo-write.md) | TodoWrite | *An agent without a plan drifts* |
| [s04](./docs/en/s04-subagent.md) | Subagents | *Break big tasks down; each subtask gets a clean context* |
| [s05](./docs/en/s05-skill-loading.md) | Skills | *Load knowledge when you need it, not upfront* |
| [s06](./docs/en/s06-context-compact.md) | Context Compact | *Context will fill up; you need a way to make room* |
| [s07](./docs/en/s07-task-system.md) | Task System | *Break big goals into small tasks, order them, persist to disk* |
| [s08](./docs/en/s08-background-tasks.md) | Background Tasks | *Run slow operations in the background; the agent keeps thinking* |
| [s09](./docs/en/s09-agent-teams.md) | Agent Teams | *When the task is too big for one, delegate to teammates* |
| [s10](./docs/en/s10-team-protocols.md) | Team Protocols | *Teammates need shared communication rules* |
| [s11](./docs/en/s11-autonomous-agents.md) | Autonomous Agents | *Teammates scan the board and claim tasks themselves* |
| [s12](./docs/en/s12-worktree-task-isolation.md) | Worktree + Task Isolation | *Each works in its own directory, no interference* |

## What's Next -- from understanding to shipping

After the 12 sessions you understand how harness engineering works inside out. Two ways to put that knowledge to work:

### Kode Agent CLI -- Open-Source Coding Agent CLI

> `npm i -g @shareai-lab/kode`

Skill & LSP support, Windows-ready, pluggable with GLM / MiniMax / DeepSeek and other open models. Install and go.

GitHub: **[shareAI-lab/Kode-cli](https://github.com/shareAI-lab/Kode-cli)**

### Kode Agent SDK -- Embed Agent Capabilities in Your App

The official Claude Code Agent SDK communicates with a full CLI process under the hood -- each concurrent user means a separate terminal process. Kode SDK is a standalone library with no per-user process overhead, embeddable in backends, browser extensions, embedded devices, or any runtime.

GitHub: **[shareAI-lab/Kode-agent-sdk](https://github.com/shareAI-lab/Kode-agent-sdk)**

---

## Sister Repo: from *on-demand sessions* to *always-on assistant*

The harness this repo teaches is **use-and-discard** -- open a terminal, give the agent a task, close when done, next session starts blank. That is the Claude Code model.

[OpenClaw](https://github.com/openclaw/openclaw) proved another possibility: on top of the same agent core, two harness mechanisms turn the agent from "poke it to make it move" into "it wakes up every 30 seconds to look for work":

- **Heartbeat** -- every 30s the harness sends the agent a message to check if there is anything to do. Nothing? Go back to sleep. Something? Act immediately.
- **Cron** -- the agent can schedule its own future tasks, executed automatically when the time comes.

Add multi-channel IM routing (WhatsApp / Telegram / Slack / Discord, 13+ platforms), persistent context memory, and a Soul personality system, and the agent goes from a disposable tool to an always-on personal AI assistant.

**[claw0](https://github.com/shareAI-lab/claw0)** is our companion teaching repo that deconstructs these harness mechanisms from scratch:

```
claw agent = agent core + heartbeat + cron + IM chat + memory + soul
```

```
learn-claude-code                   claw0
(agent harness core:                (proactive always-on harness:
 loop, tools, planning,              heartbeat, cron, IM channels,
 teams, worktree isolation)          memory, soul personality)
```

## License

MIT

---

**The model is the agent. The code is the harness. Build great harnesses. The agent will do the rest.**

**Bash is all you need. Real agents are all the universe needs.**
