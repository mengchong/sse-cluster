package com.example.sse.registry;

import com.example.sse.health.NodeHealthMonitor;
import com.example.sse.model.ConnectionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConnectionRegistry {

    private static final String CONNECTION_KEY_PREFIX = "sse:connection:";   // 连接信息 Key 前缀
    private static final String NODE_KEY_PREFIX = "sse:node:";            // 用户节点映射 Key 前缀
    private static final long CONNECTION_TTL_MINUTES = 30;               // 连接过期时间（分钟）

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private NodeHealthMonitor nodeHealthMonitor;   // 节点健康监控器

    @Value("${node.id}")
    private String currentNodeId;        // 当前节点 ID

    /**
     * 注册用户连接信息到 Redis
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    public void register(String userId, String sessionId) {
        ConnectionInfo info = new ConnectionInfo(
            userId,
            sessionId,
            currentNodeId,
            System.currentTimeMillis(),
            System.currentTimeMillis()
        );

        String connectionKey = CONNECTION_KEY_PREFIX + userId;
        String nodeKey = NODE_KEY_PREFIX + userId;

        // 保存连接信息和节点映射，设置 30 分钟过期时间
        redisTemplate.opsForValue().set(connectionKey, info, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(nodeKey, currentNodeId, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("Registered connection: userId={}, sessionId={}, nodeId={}", userId, sessionId, currentNodeId);
    }

    /**
     * 获取用户连接信息
     * @param userId 用户 ID
     * @return 连接信息
     */
    public ConnectionInfo getConnectionInfo(String userId) {
        String key = CONNECTION_KEY_PREFIX + userId;
        return (ConnectionInfo) redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取用户所在节点 ID
     * 如果节点不健康，自动清理连接信息
     * @param userId 用户 ID
     * @return 节点 ID，如果节点不健康返回 null
     */
    public String getNodeId(String userId) {
        String key = NODE_KEY_PREFIX + userId;
        String nodeId = (String) redisTemplate.opsForValue().get(key);
        
        // 检查节点健康状态
        if (nodeId != null && !nodeHealthMonitor.isNodeHealthy(nodeId)) {
            log.warn("Node {} is unhealthy, removing connection for userId: {}", nodeId, userId);
            unregister(userId);
            return null;
        }
        
        return nodeId;
    }

    /**
     * 更新用户连接心跳
     * @param userId 用户 ID
     */
    public void updateHeartbeat(String userId) {
        String connectionKey = CONNECTION_KEY_PREFIX + userId;
        ConnectionInfo info = (ConnectionInfo) redisTemplate.opsForValue().get(connectionKey);
        if (info != null) {
            // 更新心跳时间并延长过期时间
            info.setLastHeartbeat(System.currentTimeMillis());
            redisTemplate.opsForValue().set(connectionKey, info, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(NODE_KEY_PREFIX + userId, currentNodeId, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
        }
    }

    /**
     * 注销用户连接
     * @param userId 用户 ID
     */
    public void unregister(String userId) {
        String connectionKey = CONNECTION_KEY_PREFIX + userId;
        String nodeKey = NODE_KEY_PREFIX + userId;

        // 删除连接信息和节点映射
        redisTemplate.delete(connectionKey);
        redisTemplate.delete(nodeKey);

        log.info("Unregistered connection: userId={}, nodeId={}", userId, currentNodeId);
    }

    /**
     * 检查用户是否连接到当前节点
     * @param userId 用户 ID
     * @return 是否在当前节点
     */
    public boolean isLocalNode(String userId) {
        String nodeId = getNodeId(userId);
        return currentNodeId.equals(nodeId);
    }
}
