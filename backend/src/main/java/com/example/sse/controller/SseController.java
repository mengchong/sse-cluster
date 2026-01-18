package com.example.sse.controller;

import com.example.sse.manager.SseConnectionManager;
import com.example.sse.registry.ConnectionRegistry;
import com.example.sse.stream.SseMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sse")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SseController {

    @Autowired
    private SseConnectionManager connectionManager;   // SSE 连接管理器

    @Autowired
    private ConnectionRegistry connectionRegistry;    // 连接注册中心

    @Autowired
    private SseMessageSender messageSender;      // 消息发送器

    /**
     * 建立 SSE 连接
     * @param userId 用户 ID
     * @return SSE 发射器
     */
    @GetMapping("/connect/{userId}")
    public SseEmitter connect(@PathVariable String userId) {
        log.info("SSE connection request: userId={}", userId);
        return connectionManager.createConnection(userId);
    }

    /**
     * 发送消息给指定用户
     * @param request 请求参数
     * @return 响应结果
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String message = request.get("message");
        String eventName = request.get("eventName");

        log.info("Send message request: userId={}, eventName={}", userId, eventName);

        Map<String, Object> response = new HashMap<>();

        if (userId == null || message == null) {
            response.put("success", false);
            response.put("message", "userId and message are required");
            return ResponseEntity.badRequest().body(response);
        }

        String nodeId = connectionRegistry.getNodeId(userId);
        if (nodeId == null) {
            response.put("success", false);
            response.put("message", "User not connected");
            return ResponseEntity.ok(response);
        }

        if (connectionRegistry.isLocalNode(userId)) {
            // 本地节点，直接发送
            boolean sent = eventName != null && !eventName.isEmpty()
                ? connectionManager.sendMessage(userId, eventName, message)
                : connectionManager.sendMessage(userId, message);

            response.put("success", sent);
            response.put("message", sent ? "Message sent directly" : "Failed to send message");
            response.put("nodeId", nodeId);
        } else {
            // 远程节点，通过消息队列广播
            if (eventName != null && !eventName.isEmpty()) {
                messageSender.broadcast(userId, eventName, message);
            } else {
                messageSender.broadcast(userId, message);
            }

            response.put("success", true);
            response.put("message", "Message broadcasted to target node");
            response.put("nodeId", nodeId);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 触发流式输出给指定用户
     * 后端通过 SSE 连接逐字推送消息
     * @param request 请求参数
     * @return 响应结果
     */
    @PostMapping("/stream")
    public ResponseEntity<Map<String, Object>> sendStreamMessage(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");

        log.info("Send stream message request: userId={}", userId);

        Map<String, Object> response = new HashMap<>();

        if (userId == null) {
            response.put("success", false);
            response.put("message", "userId is required");
            return ResponseEntity.badRequest().body(response);
        }

        String nodeId = connectionRegistry.getNodeId(userId);
        if (nodeId == null) {
            response.put("success", false);
            response.put("message", "User not connected");
            return ResponseEntity.ok(response);
        }

        // 流式输出文本
        String streamText = "这是一个模拟的AI流式输出示例。在真实的AI应用中，这里会是AI模型生成的文本内容，逐字逐句地推送给客户端。这种流式输出方式可以提供更好的用户体验，让用户实时看到AI的响应过程。";

        if (connectionRegistry.isLocalNode(userId+"1")) {
            // 本地节点，直接流式发送
            new Thread(() -> {
                try {
                    for (int i = 0; i < streamText.length(); i++) {
                        String str = String.valueOf(streamText.charAt(i));
                        connectionManager.sendMessage(userId, "stream", str);
                        Thread.sleep(50);  // 每个字符间隔 50 毫秒
                    }
                    // 发送流式输出完成事件
                    connectionManager.sendMessage(userId, "stream-complete", "completed");
                    log.info("Stream output completed for userId: {}", userId);
                } catch (InterruptedException e) {
                    log.error("Stream output interrupted for userId: {}", userId, e);
                    Thread.currentThread().interrupt();
                }
            }).start();

            response.put("success", true);
            response.put("message", "Stream output started");
            response.put("nodeId", nodeId);
        } else {
            // 远程节点，通过消息队列通知目标节点开始流式输出
            messageSender.broadcast(userId, "start-stream", streamText);

            response.put("success", true);
            response.put("message", "Stream output request sent to target node");
            response.put("nodeId", nodeId);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 关闭指定用户的连接
     * @param userId 用户 ID
     * @return 响应结果
     */
    @PostMapping("/close/{userId}")
    public ResponseEntity<Map<String, Object>> closeConnection(@PathVariable String userId) {
        log.info("Close connection request: userId={}", userId);

        Map<String, Object> response = new HashMap<>();

        if (connectionRegistry.isLocalNode(userId)) {
            connectionManager.closeConnection(userId);
            response.put("success", true);
            response.put("message", "Connection closed locally");
        } else {
            response.put("success", false);
            response.put("message", "Connection is on another node");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 查询用户连接状态
     * @param userId 用户 ID
     * @return 连接状态信息
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<Map<String, Object>> getConnectionStatus(@PathVariable String userId) {
        Map<String, Object> response = new HashMap<>();

        String nodeId = connectionRegistry.getNodeId(userId);
        boolean isConnected = nodeId != null;
        boolean isLocal = connectionRegistry.isLocalNode(userId);

        response.put("userId", userId);
        response.put("connected", isConnected);
        response.put("nodeId", nodeId);
        response.put("isLocal", isLocal);
        response.put("localConnections", connectionManager.getConnectionCount());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取节点统计信息
     * @return 统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> response = new HashMap<>();
        response.put("localConnections", connectionManager.getConnectionCount());
        response.put("nodeId", connectionRegistry.getNodeId("test"));
        return ResponseEntity.ok(response);
    }
}
