package com.example.sse.health;

import com.example.sse.model.NodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NodeHealthMonitor {

    private static final String NODE_KEY_PREFIX = "sse:node:";              // 用户连接节点映射 Key 前缀
    private static final String NODE_INFO_KEY_PREFIX = "sse:node:info:";   // 节点信息 Key 前缀
    private static final String ALL_NODES_KEY = "sse:nodes:all";            // 所有节点集合 Key
    private static final long NODE_HEARTBEAT_INTERVAL_SECONDS = 10;          // 心跳间隔（秒）
    private static final long NODE_TIMEOUT_SECONDS = 30;                   // 节点超时时间（秒）

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${node.id}")
    private String currentNodeId;        // 当前节点 ID

    @Value("${server.port}")
    private int currentPort;             // 当前节点端口

    @Value("${server.address:localhost}")
    private String currentHost;          // 当前节点主机地址

    @PostConstruct
    public void init() {
        registerNode();    // 启动时注册节点
    }

    /**
     * 定时发送心跳到 Redis
     * 每 10 秒执行一次
     */
    @Scheduled(fixedRate = NODE_HEARTBEAT_INTERVAL_SECONDS * 1000)
    public void sendHeartbeat() {
        try {
            NodeInfo nodeInfo = new NodeInfo(
                currentNodeId,
                currentHost,
                currentPort,
                System.currentTimeMillis(),
                true
            );

            // 更新节点信息到 Redis，设置 30 秒过期时间
            redisTemplate.opsForValue().set(
                NODE_INFO_KEY_PREFIX + currentNodeId,
                nodeInfo,
                NODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

            // 将当前节点添加到所有节点集合
            redisTemplate.opsForSet().add(ALL_NODES_KEY, currentNodeId);
            redisTemplate.expire(ALL_NODES_KEY, NODE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);

            log.debug("Heartbeat sent for node: {}", currentNodeId);
        } catch (Exception e) {
            log.error("Failed to send heartbeat", e);
        }
    }

    /**
     * 定时检查所有节点健康状态
     * 每 5 秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    public void checkNodeHealth() {
        try {
            // 获取所有节点
            Set<Object> allNodes = redisTemplate.opsForSet().members(ALL_NODES_KEY);
            if (allNodes == null || allNodes.isEmpty()) {
                return;
            }

            List<String> unhealthyNodes = new ArrayList<>();
            long currentTime = System.currentTimeMillis();

            // 检查每个节点的健康状态
            for (Object nodeObj : allNodes) {
                String nodeId = (String) nodeObj;
                String key = NODE_INFO_KEY_PREFIX + nodeId;
                NodeInfo nodeInfo = (NodeInfo) redisTemplate.opsForValue().get(key);

                if (nodeInfo == null) {
                    // 节点信息不存在，标记为不健康
                    unhealthyNodes.add(nodeId);
                    log.warn("Node {} info not found, marking as unhealthy", nodeId);
                } else {
                    // 检查心跳是否超时
                    long timeSinceLastHeartbeat = currentTime - nodeInfo.getLastHeartbeat();
                    if (timeSinceLastHeartbeat > NODE_TIMEOUT_SECONDS * 1000) {
                        unhealthyNodes.add(nodeId);
                        log.warn("Node {} heartbeat timeout ({}ms), marking as unhealthy", 
                            nodeId, timeSinceLastHeartbeat);
                    }
                }
            }

            // 清理不健康节点的连接信息
            if (!unhealthyNodes.isEmpty()) {
                cleanupUnhealthyNodes(unhealthyNodes);
            }
        } catch (Exception e) {
            log.error("Error checking node health", e);
        }
    }

    /**
     * 清理不健康节点的连接信息
     * @param unhealthyNodes 不健康节点列表
     */
    private void cleanupUnhealthyNodes(List<String> unhealthyNodes) {
        for (String nodeId : unhealthyNodes) {
            try {
                log.info("Cleaning up connections for unhealthy node: {}", nodeId);

                // 查找所有属于该节点的连接
                String pattern = NODE_KEY_PREFIX + "*";
                Set<String> keys = redisTemplate.keys(pattern);
                
                if (keys != null) {
                    List<String> keysToDelete = new ArrayList<>();
                    for (String key : keys) {
                        String targetNodeId = (String) redisTemplate.opsForValue().get(key);
                        if (nodeId.equals(targetNodeId)) {
                            keysToDelete.add(key);
                        }
                    }

                    // 批量删除连接记录
                    if (!keysToDelete.isEmpty()) {
                        redisTemplate.delete(keysToDelete);
                        log.info("Deleted {} connection records for node {}", keysToDelete.size(), nodeId);
                    }
                }

                // 删除节点信息
                String nodeInfoKey = NODE_INFO_KEY_PREFIX + nodeId;
                redisTemplate.delete(nodeInfoKey);
                redisTemplate.opsForSet().remove(ALL_NODES_KEY, nodeId);

                log.info("Unhealthy node {} cleanup completed", nodeId);
            } catch (Exception e) {
                log.error("Error cleaning up node {}", nodeId, e);
            }
        }
    }

    /**
     * 获取所有健康节点列表
     * @return 健康节点列表
     */
    public List<NodeInfo> getHealthyNodes() {
        try {
            Set<Object> allNodes = redisTemplate.opsForSet().members(ALL_NODES_KEY);
            if (allNodes == null || allNodes.isEmpty()) {
                return new ArrayList<>();
            }

            List<NodeInfo> healthyNodes = new ArrayList<>();
            long currentTime = System.currentTimeMillis();

            // 筛选健康节点
            for (Object nodeObj : allNodes) {
                String nodeId = (String) nodeObj;
                String key = NODE_INFO_KEY_PREFIX + nodeId;
                NodeInfo nodeInfo = (NodeInfo) redisTemplate.opsForValue().get(key);

                if (nodeInfo != null) {
                    long timeSinceLastHeartbeat = currentTime - nodeInfo.getLastHeartbeat();
                    if (timeSinceLastHeartbeat <= NODE_TIMEOUT_SECONDS * 1000) {
                        healthyNodes.add(nodeInfo);
                    }
                }
            }

            return healthyNodes;
        } catch (Exception e) {
            log.error("Error getting healthy nodes", e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查指定节点是否健康
     * @param nodeId 节点 ID
     * @return 是否健康
     */
    public boolean isNodeHealthy(String nodeId) {
        try {
            String key = NODE_INFO_KEY_PREFIX + nodeId;
            NodeInfo nodeInfo = (NodeInfo) redisTemplate.opsForValue().get(key);

            if (nodeInfo == null) {
                return false;
            }

            // 检查心跳是否超时
            long timeSinceLastHeartbeat = System.currentTimeMillis() - nodeInfo.getLastHeartbeat();
            return timeSinceLastHeartbeat <= NODE_TIMEOUT_SECONDS * 1000;
        } catch (Exception e) {
            log.error("Error checking node health for {}", nodeId, e);
            return false;
        }
    }

    /**
     * 注册当前节点到 Redis
     */
    private void registerNode() {
        try {
            NodeInfo nodeInfo = new NodeInfo(
                currentNodeId,
                currentHost,
                currentPort,
                System.currentTimeMillis(),
                true
            );

            // 保存节点信息到 Redis
            redisTemplate.opsForValue().set(
                NODE_INFO_KEY_PREFIX + currentNodeId,
                nodeInfo,
                NODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

            // 添加到节点集合
            redisTemplate.opsForSet().add(ALL_NODES_KEY, currentNodeId);
            redisTemplate.expire(ALL_NODES_KEY, NODE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);

            log.info("Node registered: {}", currentNodeId);
        } catch (Exception e) {
            log.error("Failed to register node", e);
        }
    }
}
