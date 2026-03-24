# s02: Tool Use (ツール使用)

`s01 > [ s02 ] s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"ツールを足すなら、@Tool メソッドを1つ足すだけ"* -- ループは変わらない。新ツールは `defaultTools()` に渡すだけ。
>
> **Harness 層**: ツール分配 -- モデルが届く範囲を広げる。

## 問題

`bash` だけでは、すべての操作がシェル経由になる。`cat` は予測不能に切り詰め、`sed` は特殊文字で壊れ、すべての bash 呼び出しが制約のないセキュリティ面になる。専用ツール (`read_file`, `write_file`) ならツールレベルでパスのサンドボックス化を強制できる。

重要な洞察: ツールを追加してもループの変更は不要。

## 解決策

```
+--------+      +-------+      +--------------------+
|  User  | ---> |  LLM  | ---> | defaultTools()     |
| prompt |      |       |      | {                  |
+--------+      +---+---+      |   BashTool         |
                    ^           |   ReadFileTool     |
                    |           |   WriteFileTool    |
                    +-----------+   EditFileTool     |
                    tool_result | }                  |
                                +--------------------+

Spring AI が @Tool アノテーションで自動的に登録・分配する。
手書きの dispatch map は不要、フレームワークがツールオブジェクトのアノテーションメソッドをスキャンする。
```

## 仕組み

1. 各ツールは独立したクラスで、`@Tool` アノテーションで宣言する。`PathValidator` がパスサンドボックスでワークスペース外への脱出を防ぐ。

```java
// PathValidator -- Python 版の safe_path() 関数に相当
public class PathValidator {
    private final Path workDir;

    public Path resolve(String relativePath) {
        Path resolved = workDir.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }
}

// ReadFileTool -- Python 版の run_read() 関数に相当
public class ReadFileTool {
    private final PathValidator pathValidator;

    @Tool(description = "Read file contents. Optionally limit the number of lines returned.")
    public String readFile(
            @ToolParam(description = "Relative path to the file") String path,
            @ToolParam(description = "Maximum number of lines to read", required = false) Integer limit) {
        Path filePath = pathValidator.resolve(path);
        List<String> lines = Files.readAllLines(filePath);
        if (limit != null && limit > 0 && limit < lines.size()) {
            lines = lines.subList(0, limit);
        }
        return String.join("\n", lines);
    }
}
```

2. ツール登録は `defaultTools()` に渡すだけ。Spring AI が `@Tool` アノテーションメソッドをスキャンし、名前マッピングとパラメータバインディングを自動的に行う。

```java
// Python 版の TOOL_HANDLERS 辞書に相当
// Python: TOOL_HANDLERS = {"bash": fn, "read_file": fn, "write_file": fn, "edit_file": fn}
// Java:   ツールオブジェクトを渡すだけ、@Tool アノテーションで自動登録
this.chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(
                new BashTool(),       // bash コマンド実行
                new ReadFileTool(),   // ファイル読み取り
                new WriteFileTool(),  // ファイル書き込み
                new EditFileTool()    // ファイル編集（検索置換）
        )
        .build();
```

3. 呼び出しコードは s01 と完全に同一。ループはフレームワークが管理し、開発者はツール実装だけに集中する。

```java
// s01 との違いは defaultTools() に3つのツールオブジェクトが追加されたこと
// ループコードは完全に同一 -- これが s02 の核心的な洞察
AgentRunner.interactive("s02", userMessage ->
        chatClient.prompt()
                .user(userMessage)
                .call()
                .content()
);
```

ツール追加 = `@Tool` クラスを1つ追加 + `defaultTools()` に渡す。ループは決して変わらない。

> **TIPS — Python → Java 主要な適応ポイント:**
> - Python の `TOOL_HANDLERS` 辞書 → Spring AI `@Tool` アノテーション + `defaultTools()` 自動登録・分配
> - Python の `safe_path()` 関数 → `PathValidator` クラス（同じパス脱出チェックロジック）
> - Python の `lambda **kw` パラメータ展開 → `@ToolParam` アノテーションで自動バインディング
> - Python の `block.type == "tool_use"` 判定 → Spring AI が内部で自動検出・分配

## s01 からの変更点

| コンポーネント   | 変更前 (s01)          | 変更後 (s02)                           |
|----------------|-----------------------|----------------------------------------|
| Tools          | 1 (`BashTool`)        | 4 (`Bash`, `ReadFile`, `WriteFile`, `EditFile`) |
| Dispatch       | `defaultTools(bash)`  | `defaultTools(bash, read, write, edit)` |
| パス安全性      | なし                  | `PathValidator` サンドボックス          |
| Agent loop     | 不変                  | 不変                                   |

```java
// s01 → s02 唯一の変更: defaultTools() に3つのツールオブジェクトを追加
.defaultTools(
        new BashTool(),
        new ReadFileTool(),    // +新規追加
        new WriteFileTool(),   // +新規追加
        new EditFileTool()     // +新規追加
)
```

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s02.S02ToolUse
```

> 実行前に環境変数の設定が必要: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`

以下のプロンプトを試してみよう (英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Read the file pom.xml`
2. `Create a file called Greet.java with a greet(name) method`
3. `Edit Greet.java to add a Javadoc comment to the method`
4. `Read Greet.java to verify the edit worked`
