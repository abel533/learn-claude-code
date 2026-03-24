---
name: code-review
description: Perform thorough code reviews with security, performance, and maintainability analysis. Use when user asks to review code, check for bugs, or audit a codebase.
---

# Code Review Skill

You now have expertise in conducting comprehensive code reviews. Follow this structured approach:

## Review Checklist

### 1. Security (Critical)

Check for:
- [ ] **Injection vulnerabilities**: SQL, command, XSS, template injection
- [ ] **Authentication issues**: Hardcoded credentials, weak auth
- [ ] **Authorization flaws**: Missing access controls, IDOR
- [ ] **Data exposure**: Sensitive data in logs, error messages
- [ ] **Cryptography**: Weak algorithms, improper key management
- [ ] **Dependencies**: Known vulnerabilities (check with `mvn dependency-check:check`, `npm audit`)

```bash
# Quick security scans
mvn dependency-check:check   # Java (OWASP Dependency-Check)
mvn versions:display-dependency-updates  # Check outdated deps
npm audit                    # Node.js
grep -r "password\|secret\|api_key" --include="*.java" --include="*.properties" --include="*.yml"
```

### 2. Correctness

Check for:
- [ ] **Logic errors**: Off-by-one, null handling, edge cases
- [ ] **Race conditions**: Concurrent access without synchronization
- [ ] **Resource leaks**: Unclosed files, connections, memory
- [ ] **Error handling**: Swallowed exceptions, missing error paths
- [ ] **Type safety**: Implicit conversions, any types

### 3. Performance

Check for:
- [ ] **N+1 queries**: Database calls in loops
- [ ] **Memory issues**: Large allocations, retained references
- [ ] **Blocking operations**: Sync I/O in async code
- [ ] **Inefficient algorithms**: O(n^2) when O(n) possible
- [ ] **Missing caching**: Repeated expensive computations

### 4. Maintainability

Check for:
- [ ] **Naming**: Clear, consistent, descriptive
- [ ] **Complexity**: Functions > 50 lines, deep nesting > 3 levels
- [ ] **Duplication**: Copy-pasted code blocks
- [ ] **Dead code**: Unused imports, unreachable branches
- [ ] **Comments**: Outdated, redundant, or missing where needed

### 5. Testing

Check for:
- [ ] **Coverage**: Critical paths tested
- [ ] **Edge cases**: Null, empty, boundary values
- [ ] **Mocking**: External dependencies isolated
- [ ] **Assertions**: Meaningful, specific checks

## Review Output Format

```markdown
## Code Review: [file/component name]

### Summary
[1-2 sentence overview]

### Critical Issues
1. **[Issue]** (line X): [Description]
   - Impact: [What could go wrong]
   - Fix: [Suggested solution]

### Improvements
1. **[Suggestion]** (line X): [Description]

### Positive Notes
- [What was done well]

### Verdict
[ ] Ready to merge
[ ] Needs minor changes
[ ] Needs major revision
```

## Common Patterns to Flag

### Java
```java
// Bad: SQL injection
Statement stmt = conn.createStatement();
stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
// Good: 使用 PreparedStatement 参数化查询
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
ps.setLong(1, userId);
ResultSet rs = ps.executeQuery();

// Bad: Command injection
Runtime.getRuntime().exec("ls " + userInput);
// Good: 使用 ProcessBuilder 分离命令和参数
new ProcessBuilder("ls", userInput).start();

// Bad: 未关闭资源
InputStream is = new FileInputStream("data.txt");
// Good: 使用 try-with-resources 自动关闭
try (InputStream is = new FileInputStream("data.txt")) {
    // ...
}
```

### JavaScript/TypeScript
```javascript
// Bad: Prototype pollution
Object.assign(target, userInput)
// Good:
Object.assign(target, sanitize(userInput))

// Bad: eval usage
eval(userCode)
// Good: Never use eval with user input

// Bad: Callback hell
getData(x => process(x, y => save(y, z => done(z))))
// Good:
const data = await getData();
const processed = await process(data);
await save(processed);
```

## Review Commands

```bash
# Show recent changes
git diff HEAD~5 --stat
git log --oneline -10

# Find potential issues
grep -rn "TODO\|FIXME\|HACK\|XXX" .
grep -rn "password\|secret\|token" . --include="*.java" --include="*.properties"

# Check complexity (Java)
mvn pmd:pmd                  # 静态代码分析
mvn spotbugs:check           # Bug 检测

# Check dependencies
mvn versions:display-dependency-updates    # 检查过期依赖
mvn dependency-check:check                 # OWASP 安全扫描
```

## Review Workflow

1. **Understand context**: Read PR description, linked issues
2. **Run the code**: Build, test, run locally if possible
3. **Read top-down**: Start with main entry points
4. **Check tests**: Are changes tested? Do tests pass?
5. **Security scan**: Run automated tools
6. **Manual review**: Use checklist above
7. **Write feedback**: Be specific, suggest fixes, be kind
