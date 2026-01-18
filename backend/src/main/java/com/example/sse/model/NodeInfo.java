package com.example.sse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeInfo {
    private String nodeId;        // 节点唯一标识
    private String host;         // 节点主机地址
    private int port;            // 节点端口
    private Long lastHeartbeat;   // 最后心跳时间戳
    private boolean healthy;       // 节点是否健康
}
