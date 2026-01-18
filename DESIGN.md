# SSE 集群方案核心设计说明

## 问题分析

在集群部署环境下，使用 `ConcurrentMap` 保存 SSE 连接存在以下问题：

1. **连接信息不共享** - 每个节点独立维护连接，无法跨节点访问
2. **消息路由失败** - 请求可能被分发到没有该用户连接的节点
3. **负载均衡失效** - 无法充分利用集群的负载均衡能力

## 解决方案

### 方案架构

```
用户请求 → 负载均衡 → 任意节点
                  ↓
            Redis 查询用户所在节点
                  ↓
         ┌────────┴────────┐
         ↓                 ↓
    本地节点          远程节点
    直接发送        通过消息队列广播
```

### 核心设计

#### 1. 连接注册中心（Redis）

**数据结构：**
```
Key: sse:connection:{userId}
Value: {
  "userId": "user-123",
  "sessionId": "uuid-456",
  "nodeId": "node-1",
  "connectTime": 1234567890,
  "lastHeartbeat": 1234567890
}

Key: sse:node:{userId}
Value: "node-1"
```

**操作：**
- `register(userId, sessionId)` - 注册连接
- `getNodeId(userId)` - 查询用户所在节点
- `updateHeartbeat(userId)` - 更新心跳
- `unregister(userId)` - 注销连接

#### 2. 消息广播（RabbitMQ）

**Exchange:** fanout 类型，广播到所有节点

**Queue:** 所有节点绑定到同一队列

**消息格式：**
```json
{
  "userId": "user-123",
  "eventName": "message",
  "data": "Hello World",
  "timestamp": 1234567890
}
```

#### 3. 消息处理逻辑

```java
public void sendMessage(String userId, String message) {
    String nodeId = registry.getNodeId(userId);
    
    if (registry.isLocalNode(nodeId)) {
        // 本地节点，直接发送
        connectionManager.sendMessage(userId, message);
    } else {
        // 远程节点，通过消息队列广播
        messageSender.broadcast(userId, message);
    }
}
```

### 关键特性

#### 1. 会话保持 vs 消息路由

- **会话保持** - 简单但不推荐，节点故障时连接丢失
- **消息路由** - 本方案采用，支持真正的负载均衡

#### 2. 广播 vs 点对点

- **广播** - 所有节点都接收，简单但有冗余
- **点对点** - 只发送到目标节点，效率更高

本方案采用广播方式，因为：
- 实现简单
- 冗余影响小（消息小，节点少）
- 容错性好（节点故障不影响其他节点）

#### 3. 连接生命周期

```
建立连接 → 注册到 Redis → 设置超时（30分钟）
    ↓
心跳更新 → 延长 TTL
    ↓
连接断开 → 从 Redis 删除
```

### 性能优化

#### 1. 本地消息优化

```java
if (connectionManager.hasConnection(userId)) {
    // 直接发送，无需序列化/反序列化
    connectionManager.sendMessage(userId, message);
}
```

#### 2. 批量发送

对于 AI 流式输出，可以批量发送多个字符：

```java
StringBuilder buffer = new StringBuilder();
for (char c : text.toCharArray()) {
    buffer.append(c);
    if (buffer.length() >= 10) {
        sendMessage(userId, buffer.toString());
        buffer.setLength(0);
    }
}
```

#### 3. 连接池

使用连接池管理 Redis 和 RabbitMQ 连接：

```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
  rabbitmq:
    listener:
      simple:
        prefetch: 1
```

### 容错机制

#### 1. 节点故障

**自动检测与恢复：**
- 每个节点每 10 秒发送心跳到 Redis
- 健康监控每 5 秒检查所有节点状态
- 30 秒无心跳的节点被标记为不健康
- 自动清理故障节点的所有连接信息
- 客户端自动重连到健康节点

**故障恢复流程：**
```
Node-1 故障
    ↓
Node-1 停止发送心跳
    ↓
30秒后，NodeHealthMonitor 检测到超时
    ↓
清理 Redis 中 Node-1 的连接信息
    ↓
客户端检测到连接断开
    ↓
客户端自动重连（指数退避）
    ↓
负载均衡器分配到 Node-2 或 Node-3
    ↓
新连接建立，服务恢复
```

#### 2. Redis 故障

- 使用 Redis Sentinel 或 Cluster
- 连接注册失败时降级为本地处理

#### 3. RabbitMQ 故障

- 消息持久化
- 死信队列
- 重试机制

### 监控指标

#### 1. 连接指标
- 当前连接数
- 连接建立/断开速率
- 平均连接时长

#### 2. 消息指标
- 消息发送成功率
- 消息延迟（P50, P95, P99）
- 消息队列积压

#### 3. 节点指标
- 节点健康状态
- 负载均衡情况
- 资源使用率
- 心跳延迟

### 客户端自动重连

#### 指数退避算法

```javascript
const calculateReconnectDelay = () => {
  const delay = Math.min(
    baseReconnectDelay * Math.pow(2, reconnectAttempts),
    maxReconnectDelay
  )
  const jitter = Math.random() * 0.3 * delay
  return Math.floor(delay + jitter)
}
```

**重连延迟时间表：**
| 尝试次数 | 延迟时间（理论） | 延迟时间（实际，含抖动） |
|---------|-----------------|----------------------|
| 1 | 1 秒 | 0.85 ~ 1.15 秒 |
| 2 | 2 秒 | 1.7 ~ 2.3 秒 |
| 3 | 4 秒 | 3.4 ~ 4.6 秒 |
| 4 | 8 秒 | 6.8 ~ 9.2 秒 |
| 5 | 16 秒 | 13.6 ~ 18.4 秒 |
| 6+ | 30 秒 | 25.5 ~ 34.5 秒 |

**关键特性：**
- 指数退避：避免频繁重连冲击服务器
- 随机抖动：防止所有客户端同时重连（惊群效应）
- 最大重连次数：防止无限重连浪费资源
- 手动断开：用户主动断开时不自动重连

## 扩展性

### 1. 水平扩展

添加新节点无需修改配置，只需：
1. 启动新节点实例
2. 负载均衡器自动发现
3. 新节点自动订阅消息队列

### 2. 功能扩展

可以轻松添加：
- 消息过滤（基于用户权限）
- 消息优先级
- 消息压缩
- 消息加密

### 3. 多租户支持

在 Redis key 中添加租户标识：

```
Key: sse:connection:{tenantId}:{userId}
```

## 最佳实践

1. **连接超时** - 设置合理的超时时间（30分钟）
2. **心跳机制** - 定期更新连接状态
3. **错误处理** - 完善的异常处理和日志记录
4. **监控告警** - 实时监控关键指标
5. **压力测试** - 模拟高并发场景测试

## 总结

本方案通过 Redis + RabbitMQ 实现了 SSE 连接的跨节点共享，解决了集群部署环境下的连接管理问题。方案具有以下优势：

- 支持真正的负载均衡
- 节点故障不影响整体服务
- 易于水平扩展
- 性能和可靠性兼顾
