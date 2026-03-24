package io.mybatis.learn.core.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * JSONL消息队列 - 每个teammate一个收件箱文件
 *
 * TIPS: 对应Python的MessageBus类（s09第78-118行）。
 * Python用 open("a") 追加JSONL行，Java用 Files.writeString(APPEND)。
 * Python靠GIL隐式保证线程安全，Java用 synchronized 显式保证。
 */
public class MessageBus {
    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    public static final Set<String> VALID_MSG_TYPES = Set.of(
            "message", "broadcast", "shutdown_request",
            "shutdown_response", "plan_approval_response"
    );

    private final Path inboxDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MessageBus(Path inboxDir) {
        this.inboxDir = inboxDir;
        try {
            Files.createDirectories(inboxDir);
            if (log.isDebugEnabled()) {
                System.out.printf("🚀 消息收件箱就绪: %s%n", inboxDir);
            }
        } catch (IOException e) {
            log.error("创建消息收件箱目录失败: {}, error={}", inboxDir, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 发送消息到指定teammate的收件箱（追加JSONL行）
     */
    public synchronized String send(String sender, String to, String content,
                                    String msgType, Map<String, Object> extra) {
        if (!VALID_MSG_TYPES.contains(msgType)) {
            log.warn("发送消息失败，消息类型非法: type={}", msgType);
            return "Error: Invalid type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", msgType);
        msg.put("from", sender);
        msg.put("content", content);
        msg.put("timestamp", System.currentTimeMillis() / 1000.0);
        if (extra != null) msg.putAll(extra);

        try {
            Path inbox = inboxDir.resolve(to + ".jsonl");
            Files.writeString(inbox, mapper.writeValueAsString(msg) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (log.isDebugEnabled()) {
                System.out.printf("📨 消息已发送: %s → %s (%s)%n", sender, to, msgType);
            }
            return "Sent " + msgType + " to " + to;
        } catch (IOException e) {
            log.warn("消息发送失败: from={}, to={}, type={}, error={}", sender, to, msgType, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String send(String sender, String to, String content) {
        return send(sender, to, content, "message", null);
    }

    /**
     * 读取并清空指定teammate的收件箱（drain模式）
     */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> readInbox(String name) {
        Path inbox = inboxDir.resolve(name + ".jsonl");
        if (!Files.exists(inbox)) return List.of();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (String line : Files.readAllLines(inbox)) {
                if (!line.isBlank()) {
                    messages.add(mapper.readValue(line,
                            new TypeReference<Map<String, Object>>() {}));
                }
            }
            Files.writeString(inbox, "");
            if (!messages.isEmpty()) {
                if (log.isDebugEnabled()) {
                    System.out.printf("📬 收件箱已读取: %s (%d 条消息)%n", name, messages.size());
                }
            }
            return messages;
        } catch (IOException e) {
            log.warn("读取收件箱失败: name={}, error={}", name, e.getMessage());
            return List.of();
        }
    }

    /**
     * 广播消息给所有teammates（排除发送者自己）
     */
    public String broadcast(String sender, String content, List<String> teammates) {
        int count = 0;
        for (String name : teammates) {
            if (!name.equals(sender)) {
                send(sender, name, content, "broadcast", null);
                count++;
            }
        }
        return "Broadcast to " + count + " teammates";
    }
}
