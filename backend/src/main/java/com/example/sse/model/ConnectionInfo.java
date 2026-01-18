package com.example.sse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInfo {
    private String userId;        // 用户 ID
    private String sessionId;     // 会话 ID
    private String nodeId;       // 节点 ID
    private Long connectTime;     // 连接时间戳
    private Long lastHeartbeat;  // 最后心跳时间戳
}
