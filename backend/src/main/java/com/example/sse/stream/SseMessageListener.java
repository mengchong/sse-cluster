package com.example.sse.stream;

import com.example.sse.manager.SseConnectionManager;
import com.example.sse.model.SseMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableBinding(SseStreamChannels.class)
public class SseMessageListener {

    @Autowired
    private SseConnectionManager connectionManager;   // SSE 连接管理器

    @Autowired
    private ObjectMapper objectMapper;                // JSON 序列化工具

    @Value("${node.id}")
    private String currentNodeId;                    // 当前节点 ID

    /**
     * 处理广播消息
     * 所有节点都会收到消息，但只有持有目标用户连接的节点才会处理
     * @param message SSE 消息
     */
    @StreamListener(SseStreamChannels.SSE_BROADCAST_INPUT)
    public void handleBroadcastMessage(@Payload SseMessage message) {
        log.debug("Node {} received broadcast message: userId={}, eventName={}",
            currentNodeId, message.getUserId(), message.getEventName());

        try {
            // 检查当前节点是否有该用户的连接
            if (connectionManager.hasConnection(message.getUserId())) {
                // 处理流式输出启动请求
                if ("start-stream".equals(message.getEventName())) {
                    String streamText = (String) message.getData();
                    startStreamOutput(message.getUserId(), streamText);
                    return;
                }

                // 根据是否有事件名决定发送方式
                boolean success;
                if (message.getEventName() != null && !message.getEventName().isEmpty()) {
                    success = connectionManager.sendMessage(message.getUserId(), message.getEventName(), message.getData());
                } else {
                    success = connectionManager.sendMessage(message.getUserId(), message.getData());
                }

                if (success) {
                    log.debug("Node {} sent message successfully to userId: {}", currentNodeId, message.getUserId());
                } else {
                    log.warn("Node {} failed to send message to userId: {}", currentNodeId, message.getUserId());
                }
            } else {
                // 当前节点没有该用户的连接，忽略消息
                log.debug("Node {} has no local connection for userId: {}, ignoring message",
                    currentNodeId, message.getUserId());
            }
        } catch (Exception e) {
            log.error("Node {} error processing broadcast message for userId: {}",
                currentNodeId, message.getUserId(), e);
        }
    }

    /**
     * 启动流式输出
     * @param userId 用户 ID
     * @param streamText 流式输出文本
     */
    private void startStreamOutput(String userId, String streamText) {
        new Thread(() -> {
            try {
                log.info("Starting stream output for userId: {}", userId);
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
    }
}
