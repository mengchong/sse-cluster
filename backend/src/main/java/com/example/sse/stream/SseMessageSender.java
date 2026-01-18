package com.example.sse.stream;

import com.example.sse.model.SseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableBinding(SseStreamChannels.class)
public class SseMessageSender {

    @Autowired
    private SseStreamChannels channels;

    public void broadcast(String userId, String data) {
        broadcast(userId, null, data);
    }

    public void broadcast(String userId, String eventName, String data) {
        SseMessage message = new SseMessage(
            userId,
            eventName,
            data,
            System.currentTimeMillis()
        );

        Message<SseMessage> msg = MessageBuilder.withPayload(message).build();
        boolean sent = channels.broadcastOutput().send(msg);

        if (sent) {
            log.info("Broadcast message sent: userId={}, eventName={}", userId, eventName);
        } else {
            log.error("Failed to broadcast message: userId={}, eventName={}", userId, eventName);
        }
    }
}
