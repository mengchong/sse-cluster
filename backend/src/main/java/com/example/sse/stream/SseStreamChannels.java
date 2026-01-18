package com.example.sse.stream;

import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;

public interface SseStreamChannels {

    String SSE_BROADCAST_OUTPUT = "sseBroadcastOutput";
    String SSE_BROADCAST_INPUT = "sseBroadcastInput";

    @Output(SSE_BROADCAST_OUTPUT)
    MessageChannel broadcastOutput();

    @Input(SSE_BROADCAST_INPUT)
    SubscribableChannel broadcastInput();
}
