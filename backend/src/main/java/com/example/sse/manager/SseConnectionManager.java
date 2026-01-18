package com.example.sse.manager;

import com.example.sse.registry.ConnectionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SseConnectionManager {

    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();      // sessionId -> SseEmitter 映射
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();  // userId -> sessionId 映射
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();  // 心跳执行器

    @Autowired
    private ConnectionRegistry connectionRegistry;    // 连接注册中心

    /**
     * 创建 SSE 连接
     * @param userId 用户 ID
     * @return SSE 发射器
     */
    public SseEmitter createConnection(String userId) {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);  // 30 分钟超时

        // 连接完成回调
        emitter.onCompletion(() -> {
            log.info("SSE connection completed: userId={}, sessionId={}", userId, sessionId);
            cleanup(userId, sessionId);
        });

        // 连接超时回调
        emitter.onTimeout(() -> {
            log.info("SSE connection timeout: userId={}, sessionId={}", userId, sessionId);
            cleanup(userId, sessionId);
        });

        // 连接错误回调
        emitter.onError((ex) -> {
            log.error("SSE connection error: userId={}, sessionId={}", userId, sessionId, ex);
            cleanup(userId, sessionId);
        });

        // 保存连接到内存
        connections.put(sessionId, emitter);
        userSessionMap.put(userId, sessionId);

        // 注册到 Redis
        connectionRegistry.register(userId, sessionId);

        try {
            // 发送连接成功事件
            emitter.send(SseEmitter.event()
                .name("connected")
                .data("{\"sessionId\":\"" + sessionId + "\",\"nodeId\":\"" + connectionRegistry.getNodeId(userId) + "\"}"));
        } catch (IOException e) {
            log.error("Failed to send connected event", e);
            cleanup(userId, sessionId);
        }

        log.info("SSE connection created: userId={}, sessionId={}", userId, sessionId);
        return emitter;
    }

    /**
     * 发送消息给指定用户
     * @param userId 用户 ID
     * @param message 消息内容
     * @return 是否发送成功
     */
    public boolean sendMessage(String userId, String message) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId == null) {
            log.warn("No session found for userId: {}", userId);
            return false;
        }

        SseEmitter emitter = connections.get(sessionId);
        if (emitter == null) {
            log.warn("No emitter found for sessionId: {}", sessionId);
            return false;
        }

        try {
            emitter.send(SseEmitter.event().data(message));
            connectionRegistry.updateHeartbeat(userId);  // 更新心跳
            return true;
        } catch (IOException e) {
            log.error("Failed to send message to userId: {}", userId, e);
            cleanup(userId, sessionId);
            return false;
        }
    }

    /**
     * 发送事件消息给指定用户
     * @param userId 用户 ID
     * @param eventName 事件名称
     * @param data 事件数据
     * @return 是否发送成功
     */
    public boolean sendMessage(String userId, String eventName, Object data) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId == null) {
            log.warn("No session found for userId: {}", userId);
            return false;
        }

        SseEmitter emitter = connections.get(sessionId);
        if (emitter == null) {
            log.warn("No emitter found for sessionId: {}", sessionId);
            return false;
        }

        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            connectionRegistry.updateHeartbeat(userId);  // 更新心跳
            return true;
        } catch (IOException e) {
            log.error("Failed to send event to userId: {}", userId, e);
            cleanup(userId, sessionId);
            return false;
        }
    }

    /**
     * 关闭指定用户的连接
     * @param userId 用户 ID
     */
    public void closeConnection(String userId) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId != null) {
            cleanup(userId, sessionId);
        }
    }

    /**
     * 清理连接资源
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    private void cleanup(String userId, String sessionId) {
        SseEmitter emitter = connections.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.error("Error completing emitter", e);
            }
        }
        userSessionMap.remove(userId);
        connectionRegistry.unregister(userId);  // 从 Redis 注销
    }

    /**
     * 获取当前连接数
     * @return 连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * 检查是否有指定用户的连接
     * @param userId 用户 ID
     * @return 是否有连接
     */
    public boolean hasConnection(String userId) {
        return userSessionMap.containsKey(userId);
    }
}
