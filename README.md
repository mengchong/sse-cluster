# SSE 集群流式输出方案

基于 Redis + RabbitMQ 的跨节点 SSE 连接共享解决方案

## 项目架构

```
┌─────────────┐
│   负载均衡   │
└──────┬──────┘
       │
       ├──────────────┬──────────────┐
       │              │              │
   ┌───▼───┐      ┌───▼───┐      ┌───▼───┐
   │ 节点 A │      │ 节点 B │      │ 节点 C │
   └───┬───┘      └───┬───┘      └───┬───┘
       │              │              │
       └──────────────┼──────────────┘
                      │
              ┌───────▼───────┐
              │   Redis       │  连接注册中心
              └───────┬───────┘
                      │
              ┌───────▼───────┐
              │   RabbitMQ    │  消息广播
              └───────────────┘
```

## 核心组件

### 1. ConnectionRegistry（连接注册中心）
- 使用 Redis 存储用户连接信息
- 记录 userId -> nodeId 映射关系
- 支持心跳更新和连接清理
- 自动检测不健康节点并清理连接

### 2. SseConnectionManager（连接管理器）
- 管理本地 SSE 连接
- 提供消息发送接口
- 处理连接生命周期

### 3. SseMessageSender/Listener（消息广播）
- 使用 RabbitMQ 广播消息
- 所有节点监听同一队列
- 根据目标节点决定是否处理消息

### 4. NodeHealthMonitor（节点健康监控）
- 定期发送心跳到 Redis
- 监控所有节点健康状态
- 自动清理故障节点的连接信息
- 支持节点故障自动恢复

### 5. SseController（API 端点）
- `/api/sse/connect/{userId}` - 建立 SSE 连接
- `/api/sse/send` - 发送消息
- `/api/sse/close/{userId}` - 关闭连接
- `/api/sse/status/{userId}` - 查询连接状态

### 6. 前端自动重连机制
- 指数退避算法（Exponential Backoff）
- 随机抖动避免惊群效应
- 最多重连 10 次
- 手动断开时不自动重连

## 工作流程

### 建立连接
1. 用户请求 SSE 连接
2. 节点 A 创建 SseEmitter
3. 节点 A 将连接信息注册到 Redis
4. 返回连接给用户

### 发送消息
1. 请求到达任意节点（如节点 B）
2. 查询 Redis 获取用户所在节点
3. 如果是本地节点 → 直接发送
4. 如果是远程节点 → 通过 RabbitMQ 广播
5. 目标节点接收消息并推送给用户

## 快速开始

### 环境要求
- JDK 11+
- Maven 3.6+
- Redis 6.0+
- RabbitMQ 3.8+
- Node.js 16+

### 后端启动

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

启动多个节点（不同端口）：
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --node.id=node-1"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 --node.id=node-2"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8083 --node.id=node-3"
```

### 前端启动

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:3000

## 测试场景

### 场景 1：本地节点消息
1. 用户 A 连接到节点 1
2. 向用户 A 发送消息（请求到达节点 1）
3. 消息直接发送，无需经过消息队列

### 场景 2：跨节点消息
1. 用户 A 连接到节点 1
2. 向用户 A 发送消息（请求到达节点 2）
3. 节点 2 查询 Redis 发现用户在节点 1
4. 节点 2 通过 RabbitMQ 广播消息
5. 节点 1 接收消息并推送给用户 A

### 场景 3：AI 流式输出
1. 用户连接到任意节点
2. AI 服务生成内容并逐字推送
3. 无论请求到达哪个节点，消息都能正确路由

### 场景 4：节点故障恢复
1. 用户 A 连接到节点 1
2. 节点 1 突然故障
3. NodeHealthMonitor 检测到节点 1 超时（30秒）
4. 自动清理 Redis 中节点 1 的所有连接信息
5. 客户端检测到连接断开，自动重连
6. 负载均衡器将请求分配到节点 2 或节点 3
7. 新连接建立，服务恢复

### 场景 5：客户端自动重连
1. 客户端连接断开（网络故障或节点故障）
2. 客户端自动触发重连（指数退避）
3. 第1次重连：1秒后
4. 第2次重连：2秒后
5. 第3次重连：4秒后
6. ...
7. 最多重连10次后放弃

## 配置说明

### application.yml

```yaml
spring:
  redis:
    host: localhost
    port: 6379
  rabbitmq:
    host: localhost
    port: 5672

server:
  port: ${SERVER_PORT:8080}
  address: ${SERVER_ADDRESS:localhost}

node:
  id: ${NODE_ID:node-${random.uuid}}
  health:
    heartbeat-interval: 10s    # 心跳间隔
    timeout: 30s               # 节点超时时间
    check-interval: 5s         # 健康检查间隔
```

### 跨域配置（CORS）

项目已配置跨域支持，允许前端从不同端口访问后端 API：

**后端配置：**
- 全局 CORS 配置：[CorsConfig.java 上的 `@CrossOrigin` 注解

**前端配置：**
- Vite 代理：[vite.config.js]中的代理配置

**CORS 配置详情：**
```java
@CrossOrigin(origins = "*", maxAge = 3600)
```

允许：
- 所有来源（origins: "*"）
- 所有方法（GET, POST, PUT, DELETE, OPTIONS）
- 所有请求头
- 携带凭证（allowCredentials: true）
- 预检缓存时间：1 小时（3600 秒）

### 环境变量
- `SERVER_PORT` - 服务端口
- `NODE_ID` - 节点唯一标识

## 技术栈

- Spring Boot 2.7.18
- Spring Data Redis
- Spring Cloud Stream (RabbitMQ)
- Vue 3
- Vite

## 优势

1. **支持集群部署** - 多节点间连接信息共享
2. **负载均衡友好** - 请求可分发到任意节点
3. **高可用性** - 节点故障不影响其他节点
4. **可扩展** - 支持水平扩展
5. **低延迟** - 本地消息直接发送，跨节点通过消息队列

## 注意事项

1. Redis 和 RabbitMQ 需要高可用部署
2. 连接超时时间建议设置为 30 分钟
3. 生产环境建议增加认证和加密
4. 监控 Redis 和 RabbitMQ 的性能指标

