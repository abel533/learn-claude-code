# s01: The Agent Loop (エージェントループ)

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- 1つのツール + 1つのループ = エージェント。
>
> **Harness 層**: ループ -- モデルと現実世界を繋ぐ最初の接点。

## 問題

言語モデルはコードについて推論できるが、現実世界に触れられない -- ファイルを読めず、テストを実行できず、エラーを確認できない。ループがなければ、ツール呼び出しのたびに手動で結果を貼り戻す必要がある。あなた自身がそのループになる。

## 解決策

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() がツール呼び出しがなくなるまで自動ループ)
```

1つの `call()` 呼び出しがフロー全体を制御する。Spring AI が自動的にループし、モデルがツール呼び出しを止めるまで続ける。

## 仕組み

### 1. ChatClient の構築：モデル注入 + ツール登録

Spring Boot の自動設定で `ChatModel` を注入し、`ChatClient.builder()` でクライアントを構築、システムプロンプトとツールを設定する。

```java
// TIP: Python 版ではモジュールレベルで client = Anthropic() と MODEL を作成。
// Spring AI は自動設定で ChatModel を注入し、builder で ChatClient を構築する。
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // @Tool アノテーション付きツールオブジェクト
            .build();
}
```

### 2. `@Tool` アノテーション：宣言的ツール登録

Spring AI は `@Tool` アノテーションでツールを自動的に検出・登録する。起動時にフレームワークが `defaultTools()` に渡されたオブジェクトをスキャンし、すべての `@Tool` メソッドのシグネチャと説明を抽出し、LLM が必要とするツールスキーマ（名前、パラメータ、説明）を生成して、毎回の `call()` リクエストに自動的に含める。

```java
// BashTool -- Python 版の run_bash() 関数に相当
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // 危険コマンドチェック + ProcessBuilder 実行 + タイムアウト制御 + 出力切り詰め
        // ...
    }
}
```

> Python の手動登録方式との比較：
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: `@Tool` + `@ToolParam` アノテーションだけで、フレームワークがスキーマ生成とメソッドディスパッチを自動化

### 3. Spring AI 内部自動ループ：`call()` の内部実装

**これが Java 版と Python 版の最も重要な違いだ。** Python 版ではツール呼び出しを駆動するために手書きの while ループが必要：

```python
# Python 版 -- 手動ループ
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # assistant メッセージを収集
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # モデルがツールを呼ばなくなった、ループ終了
        # ツールを実行して結果を返送
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI の `ChatClient.call()` は**完全に等価なロジックを内部にカプセル化**している：

```
call() 内部フロー:
  ┌─────────────────────────────────────────────────────┐
  │  1. リクエスト組み立て: system prompt + user msg + tools │
  │  2. LLM に送信                                      │
  │  3. レスポンス解析                                   │
  │     ├── tool_use あり? ──→ はい:                    │
  │     │   a. ツール名と引数を抽出                      │
  │     │   b. リフレクションで対応する @Tool メソッドを呼出 │
  │     │   c. tool_result をメッセージリストに追加       │
  │     │   d. ステップ 2 に戻る（自動ループ）           │
  │     └── いいえ ──→ 最終テキストを返す               │
  └─────────────────────────────────────────────────────┘
```

キーポイント：
- **ツール検出**: Spring AI はレスポンスに `tool_use` タイプのコンテンツブロックがあるかチェック（Python の `stop_reason == "tool_use"` に相当）
- **リフレクションディスパッチ**: フレームワークが Java リフレクションで、LLM が返したツール名に対応する `@Tool` メソッドを見つけて呼び出す（Python の `TOOL_HANDLERS[block.name]` に相当）
- **結果返送**: ツール実行結果は自動的に `tool_result` メッセージとして会話に追加（Python が手動で `tool_result` コンテンツブロックを構築するのに相当）
- **ループ終了**: モデルが純粋なテキスト（ツール呼び出しなし）を返すと、`call()` が最終結果を返す

従って、Python 版の約15行の while ループは、Java 版では1行の `.call()` に凝縮される。

### 4. `AgentRunner.interactive()`：REPL インタラクションループ

`AgentRunner` は全レッスン共通の REPL（Read-Eval-Print Loop）ユーティリティクラスで、Python の `if __name__ == "__main__"` 内の `input()` ループに相当する。

```java
public class AgentRunner {
    /**
     * インタラクティブ REPL ループを開始。
     * @param prefix  プロンプトプレフィックス（例: "s01"）
     * @param handler ユーザー入力を処理し Agent レスポンスを返す関数
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("'q' または 'exit' で終了");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // カラープロンプト
            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().trim();
            } catch (Exception e) {
                break;
            }
            if (input.isEmpty() || "exit".equalsIgnoreCase(input) || "q".equalsIgnoreCase(input)) {
                break;
            }
            try {
                String response = handler.apply(input);  // Agent ハンドラーを呼び出し
                if (response != null && !response.isBlank()) {
                    System.out.println(response);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        System.out.println("Bye!");
    }
}
```

ワークフロー：`Scanner` で入力読み取り → `handler.apply()` で Agent に送信 → レスポンス出力 → ループ。`handler` は関数型インターフェースで、各レッスンが自分の Agent 呼び出しロジックを渡す。

### 5. 完全な Agent クラスとして組み立て

```java
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S01AgentLoop implements CommandLineRunner {

    private final ChatClient chatClient;

    public S01AgentLoop(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at ...")
                .defaultTools(new BashTool())
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s01", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()       // ← この1つの呼び出し = Python の while ループ全体
                        .content()
        );
    }
}
```

> **TIPS — Python → Java 主要な適応ポイント:**
> - Python の `while True` + `stop_reason` 手動ループ → Spring AI `ChatClient.call()` 内蔵自動ループ
> - Python の `TOOLS` 配列 + `TOOL_HANDLERS` 辞書 → `@Tool` アノテーション + `defaultTools()` 自動登録とリフレクションディスパッチ
> - Python の `client = Anthropic()` → Spring Boot 自動設定で `ChatModel` を注入
> - Python の `input()` インタラクション → `AgentRunner.interactive()` が Scanner REPL + 関数型インターフェースをカプセル化

コアコード40行未満、これがエージェント全体だ。残り11章はすべてこのループの上にメカニズムを積み重ねる -- ループ自体は決して変わらない。

## 変更点

| コンポーネント  | 変更前     | 変更後                                           |
|---------------|------------|--------------------------------------------------|
| Agent loop    | (なし)     | `ChatClient.call()` 内蔵ツールループ             |
| Tools         | (なし)     | `BashTool` (単一の `@Tool` ツール)               |
| Messages      | (なし)     | Spring AI が内部でメッセージリストを管理          |
| Control flow  | (なし)     | フレームワークが自動判定: ツール呼び出しなしで最終テキストを返す |

```java
// コアコード -- 構築 + 呼び出し
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> 実行前に環境変数の設定が必要: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **デフォルトプロトコルは OpenAI**（OpenAI 公式、Azure OpenAI、OpenAI 互換インターフェースを提供するサードパーティモデルサービスなど、すべての OpenAI API 形式のサービスに対応）。
> Anthropic プロトコル（Claude ネイティブ API）を使用する場合は、以下のセクションを展開してください。

<details>
<summary><strong>AI プロトコルの切り替え（OpenAI ↔ Anthropic）</strong></summary>

このプロジェクトは **Spring AI の Starter 依存 + 設定ファイル** で基盤プロトコルを切り替える。Java ビジネスコード（`ChatModel`、`ChatClient`）は**変更不要**。

#### 方式 1：OpenAI プロトコル（デフォルト）

`pom.xml` の依存：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` の設定：

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-xxx}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
```

環境変数の例：

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # 任意の OpenAI 互換エンドポイントに変更可
export AI_MODEL=gpt-4o
```

> **TIP**: 多くのサードパーティモデルサービス（DeepSeek、Mistral、Qwen など）が OpenAI 互換 API を提供している。`AI_BASE_URL` と `AI_MODEL` を変更するだけで接続でき、プロトコル切り替えは不要。

#### 方式 2：Anthropic プロトコル（Claude ネイティブ API）

**ステップ 1**：`pom.xml` を編集 — OpenAI starter を Anthropic starter に置き換え：

```xml
<!-- OpenAI starter をコメントアウトまたは削除 -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- Anthropic starter を追加 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**ステップ 2**：`application.yml` を編集 — `spring.ai.openai` を `spring.ai.anthropic` に置き換え：

```yaml
spring:
  ai:
    anthropic:
      api-key: ${AI_API_KEY}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
```

**ステップ 3**：環境変数を設定：

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### 切り替えの仕組み

Spring AI の `ChatModel` は統一された抽象インターフェース。異なる Starter が異なる実装を提供する：

| Starter 依存 | 自動注入される ChatModel 実装 | 設定プレフィックス |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

ビジネスコードは常に `ChatModel` インターフェースに対してプログラムする。プロトコル切り替えには依存と設定の変更だけが必要で、Java コードの変更は不要。

</details>

以下のプロンプトを試してみよう(英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`
