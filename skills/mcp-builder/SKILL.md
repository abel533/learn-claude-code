---
name: mcp-builder
description: Build MCP (Model Context Protocol) servers that give Claude new capabilities. Use when user wants to create an MCP server, add tools to Claude, or integrate external services.
---

# MCP Server Building Skill

You now have expertise in building MCP (Model Context Protocol) servers. MCP enables Claude to interact with external services through a standardized protocol.

## What is MCP?

MCP servers expose:
- **Tools**: Functions Claude can call (like API endpoints)
- **Resources**: Data Claude can read (like files or database records)
- **Prompts**: Pre-built prompt templates

## Quick Start: Java/Spring AI MCP Server

### 1. Project Setup

```bash
# 使用 Spring Initializr 创建项目
# 或通过 Maven 手动创建
mkdir my-mcp-server && cd my-mcp-server
mvn archetype:generate -DgroupId=com.example -DartifactId=my-mcp-server \
    -DarchetypeArtifactId=maven-archetype-quickstart -DinteractiveMode=false
```

在 `pom.xml` 中添加依赖:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

### 2. Basic Server Template

```java
// src/main/java/com/example/McpServerApplication.java
package com.example;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public MyTools myTools() {
        return new MyTools();
    }
}

// 定义工具类
class MyTools {

    @Tool(description = "Say hello to someone")
    public String hello(@ToolParam(description = "The name to greet") String name) {
        return "Hello, " + name + "!";
    }

    @Tool(description = "Add two numbers together")
    public String addNumbers(
            @ToolParam(description = "First number") int a,
            @ToolParam(description = "Second number") int b) {
        return String.valueOf(a + b);
    }
}
```

配置 `application.properties`:
```properties
spring.ai.mcp.server.name=my-server
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.main.web-application-type=none
spring.main.banner-mode=off
spring.ai.mcp.server.stdio=true
```

### 3. Register with Claude

Add to `~/.claude/mcp.json`:
```json
{
  "mcpServers": {
    "my-server": {
      "command": "java",
      "args": ["-jar", "/path/to/my-mcp-server/target/my-mcp-server.jar"]
    }
  }
}
```

## TypeScript MCP Server

### 1. Setup

```bash
mkdir my-mcp-server && cd my-mcp-server
npm init -y
npm install @modelcontextprotocol/sdk
```

### 2. Template

```typescript
// src/index.ts
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const server = new Server({
  name: "my-server",
  version: "1.0.0",
});

// Define tools
server.setRequestHandler("tools/list", async () => ({
  tools: [
    {
      name: "hello",
      description: "Say hello to someone",
      inputSchema: {
        type: "object",
        properties: {
          name: { type: "string", description: "Name to greet" },
        },
        required: ["name"],
      },
    },
  ],
}));

server.setRequestHandler("tools/call", async (request) => {
  if (request.params.name === "hello") {
    const name = request.params.arguments.name;
    return { content: [{ type: "text", text: `Hello, ${name}!` }] };
  }
  throw new Error("Unknown tool");
});

// Start server
const transport = new StdioServerTransport();
server.connect(transport);
```

## Advanced Patterns

### External API Integration

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WeatherTools {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Get current weather for a city")
    public String getWeather(@ToolParam(description = "City name") String city) {
        String url = "https://api.weatherapi.com/v1/current.json?key=YOUR_API_KEY&q=" + city;
        String response = restTemplate.getForObject(url, String.class);
        JsonNode data = objectMapper.readTree(response);
        JsonNode current = data.get("current");
        return String.format("%s: %s°C, %s",
                city, current.get("temp_c"), current.get("condition").get("text").asText());
    }
}
```

### Database Access

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

public class DatabaseTools {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "Execute a read-only SQL query")
    public String queryDb(@ToolParam(description = "SQL query to execute") String sql) {
        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            return "Error: Only SELECT queries allowed";
        }
        var rows = jdbcTemplate.queryForList(sql);
        return rows.toString();
    }
}
```

### Resources (Read-only Data)

```java
import org.springframework.ai.tool.annotation.Tool;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourceTools {

    @Tool(description = "Read application settings")
    public String getSettings() throws Exception {
        return Files.readString(Path.of("settings.json"));
    }

    @Tool(description = "Read a file from the workspace")
    public String readFile(@ToolParam(description = "Path to the file") String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
```

## Testing

```bash
# Build the project
mvn clean package -DskipTests

# Test with MCP Inspector
npx @anthropics/mcp-inspector java -jar target/my-mcp-server.jar

# Or send test messages directly
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -jar target/my-mcp-server.jar
```

## Best Practices

1. **Clear tool descriptions**: Claude uses `@Tool(description=...)` to decide when to call tools
2. **Input validation**: Always validate and sanitize inputs in tool methods
3. **Error handling**: Return meaningful error messages, use proper exception handling
4. **Use Spring DI**: Leverage Spring's dependency injection for service wiring
5. **Security**: Never expose sensitive operations without auth
6. **Idempotency**: Tools should be safe to retry
