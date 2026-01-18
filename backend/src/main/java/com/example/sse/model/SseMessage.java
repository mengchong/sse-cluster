package com.example.sse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseMessage {
    private String userId;
    private String eventName;
    private String data;
    private Long timestamp;
}
