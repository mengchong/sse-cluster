<template>
  <div class="container">
    <div class="header">
      <h1>SSE 集群流式输出演示</h1>
      <p>基于 Redis + RabbitMQ 的跨节点 SSE 连接共享方案</p>
    </div>

    <div class="card">
      <h2>连接管理</h2>
      <div class="form-group">
        <label>用户ID</label>
        <input 
          v-model="userId" 
          type="text" 
          placeholder="请输入用户ID"
          :disabled="isConnected"
        >
      </div>
      <div>
        <button 
          class="btn btn-primary" 
          @click="connect" 
          :disabled="isConnected || !userId"
        >
          {{ isConnected ? '已连接' : '建立 SSE 连接' }}
        </button>
        <button 
          class="btn btn-danger" 
          @click="disconnect" 
          :disabled="!isConnected"
        >
          断开连接
        </button>
        <button 
          class="btn btn-success" 
          @click="checkStatus" 
          :disabled="!userId"
        >
          查看状态
        </button>
        <span class="status" :class="{ connected: isConnected, disconnected: !isConnected }">
          {{ isConnected ? '已连接' : '未连接' }}
        </span>
      </div>

      <div v-if="reconnectInfo.attempts > 0 && !isConnected" class="info-grid" :style="{
        marginTop: '16px', 
        background: reconnectInfo.failed ? '#f8d7da' : '#fff3cd', 
        border: '1px solid ' + (reconnectInfo.failed ? '#f5c6cb' : '#ffc107')
      }">
        <div class="info-item">
          <label>重连状态</label>
          <div class="value" :style="{ color: reconnectInfo.failed ? '#721c24' : '#856404' }">
            {{ reconnectInfo.failed ? '重连失败' : '重连中...' }}
          </div>
        </div>
        <div class="info-item">
          <label>重连次数</label>
          <div class="value">{{ reconnectInfo.attempts }} / {{ reconnectInfo.maxAttempts }}</div>
        </div>
        <div class="info-item" v-if="!reconnectInfo.failed">
          <label>下次重连</label>
          <div class="value">{{ (reconnectInfo.nextDelay / 1000).toFixed(1) }} 秒</div>
        </div>
        <div class="info-item" v-if="reconnectInfo.failed">
          <label>失败原因</label>
          <div class="value">已达到最大重连次数</div>
        </div>
      </div>

      <div v-if="connectionInfo" class="info-grid">
        <div class="info-item">
          <label>用户ID</label>
          <div class="value">{{ connectionInfo.userId }}</div>
        </div>
        <div class="info-item">
          <label>连接状态</label>
          <div class="value">{{ connectionInfo.connected ? '已连接' : '未连接' }}</div>
        </div>
        <div class="info-item">
          <label>节点ID</label>
          <div class="value">{{ connectionInfo.nodeId || '-' }}</div>
        </div>
        <div class="info-item">
          <label>本地连接</label>
          <div class="value">{{ connectionInfo.isLocal ? '是' : '否' }}</div>
        </div>
        <div class="info-item">
          <label>本地连接数</label>
          <div class="value">{{ connectionInfo.localConnections }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>发送消息</h2>
      <div class="form-group">
        <label>目标用户ID</label>
        <input 
          v-model="targetUserId" 
          type="text" 
          placeholder="请输入目标用户ID"
        >
      </div>
      <div class="form-group">
        <label>事件名称（可选）</label>
        <input 
          v-model="eventName" 
          type="text" 
          placeholder="例如: message, progress, error"
        >
      </div>
      <div class="form-group">
        <label>消息内容</label>
        <textarea 
          v-model="messageContent" 
          placeholder="请输入要发送的消息内容"
        ></textarea>
      </div>
      <button 
        class="btn btn-primary" 
        @click="sendMessage" 
        :disabled="!targetUserId || !messageContent || sending"
      >
        {{ sending ? '发送中...' : '发送消息' }}
      </button>
      <button 
        class="btn btn-success" 
        @click="sendStreamMessage" 
        :disabled="!targetUserId || streaming"
      >
        {{ streaming ? '流式发送中...' : '模拟 AI 流式输出' }}
      </button>

      <div v-if="sendResult" class="info-grid" style="margin-top: 16px;">
        <div class="info-item">
          <label>发送状态</label>
          <div class="value" :style="{ color: sendResult.success ? '#10b981' : '#ef4444' }">
            {{ sendResult.success ? '成功' : '失败' }}
          </div>
        </div>
        <div class="info-item">
          <label>消息</label>
          <div class="value">{{ sendResult.message }}</div>
        </div>
        <div class="info-item" v-if="sendResult.nodeId">
          <label>目标节点</label>
          <div class="value">{{ sendResult.nodeId }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>接收消息</h2>
      <div class="messages">
        <div v-if="messages.length === 0" class="empty-state">
          暂无消息，请先建立 SSE 连接
        </div>
        <div v-for="(msg, index) in messages" :key="index" class="message">
          <div class="timestamp">{{ formatTime(msg.timestamp) }}</div>
          <div class="content">
            <strong v-if="msg.eventName">{{ msg.eventName }}:</strong> {{ msg.data }}
          </div>
        </div>
      </div>
      <div style="margin-top: 16px;">
        <button class="btn btn-danger" @click="clearMessages">清空消息</button>
        <span style="margin-left: 10px; color: #666;">共 {{ messages.length }} 条消息</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import axios from 'axios'

// 响应式状态
const userId = ref('user-' + Math.random().toString(36).substr(2, 9))      // 用户 ID
const targetUserId = ref('')                                             // 目标用户 ID
const eventName = ref('')                                                // 事件名称
const messageContent = ref('')                                             // 消息内容
const isConnected = ref(false)                                            // 是否已连接
const isConnecting = ref(false)                                           // 是否正在连接
const sending = ref(false)                                               // 是否正在发送
const streaming = ref(false)                                              // 是否正在流式发送
const messages = ref([])                                                 // 消息列表
const connectionInfo = ref(null)                                           // 连接信息
const sendResult = ref(null)                                              // 发送结果
const reconnectInfo = ref({                                                // 重连信息
  attempts: 0,
  maxAttempts: 10,
  nextDelay: 0
})
const streamBuffer = ref('')                                             // 流式输出缓冲区

// 内部变量
let eventSource = null                      // SSE 事件源
let reconnectTimer = null                    // 重连定时器
let reconnectAttempts = 0                     // 当前重连次数
let maxReconnectAttempts = 10                 // 最大重连次数
let baseReconnectDelay = 1000                // 基础重连延迟（毫秒）
let maxReconnectDelay = 30000                 // 最大重连延迟（毫秒）
let shouldReconnect = true                    // 是否应该重连
let manualDisconnect = false                  // 是否手动断开

/**
 * 建立 SSE 连接
 * @param isReconnect 是否为重连（默认 false）
 */
const connect = (isReconnect = false) => {
  if (!userId.value || isConnecting.value) return

  if (!isReconnect) {
    resetReconnectState()          // 仅在首次连接时重置重连状态
  }
  isConnecting.value = true
  messages.value = []

  // 创建 SSE 连接
  eventSource = new EventSource(`http://localhost:8080/api/sse/connect/${userId.value}`)

  // 连接成功回调
  eventSource.onopen = () => {
    isConnected.value = true
    isConnecting.value = false
    reconnectAttempts = 0
    reconnectInfo.value = {
      attempts: 0,
      maxAttempts: maxReconnectAttempts,
      nextDelay: 0
    }
    streamBuffer.value = ''  // 清空流式缓冲区
    console.log('SSE connection established')
  }

  // 接收消息回调
  eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data)
    console.log('SSE message received:', data)
    
    if (data.sessionId) {
      // 连接确认消息
      console.log('Connected with sessionId:', data.sessionId, 'nodeId:', data.nodeId)
    } else {
      // 普通消息
      messages.value.push({
        timestamp: Date.now(),
        eventName: null,
        data: data
      })
    }
  }

  // 连接事件监听
  eventSource.addEventListener('connected', (event) => {
    const data = JSON.parse(event.data)
    console.log('Connected event:', data)
  })

  // 监听流式输出事件
  eventSource.addEventListener('stream', (event) => {
    const char = event.data
    console.log('Stream char received:', char)
    
    // 累积字符到缓冲区
    streamBuffer.value += char
    
    // 查找或创建流式消息条目
    let streamMessage = messages.value.find(msg => msg.eventName === 'stream')
    
    if (!streamMessage) {
      // 如果没有流式消息，创建新的
      streamMessage = {
        timestamp: Date.now(),
        eventName: 'stream',
        data: char
      }
      messages.value.push(streamMessage)
    } else {
      // 更新现有流式消息的内容
      streamMessage.data = streamBuffer.value
    }
  })

  // 监听流式输出完成事件
  eventSource.addEventListener('stream-complete', (event) => {
    console.log('Stream output completed')
    streaming.value = false  // 停止流式输出状态
  })

  // 监听其他自定义事件
  eventSource.addEventListener('message', (event) => {
    const data = event.data
    console.log('Custom message event received:', data)
    messages.value.push({
      timestamp: Date.now(),
      eventName: 'message',
      data: data
    })
  })

  // 连接错误回调
  eventSource.onerror = (error) => {
    console.error('SSE connection error:', error)
    isConnected.value = false
    isConnecting.value = false
    
    // 关闭旧连接
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }

    // 如果不是手动断开且允许重连，则调度重连
    if (!manualDisconnect && shouldReconnect) {
      scheduleReconnect()
    }
  }
}

/**
 * 断开 SSE 连接
 */
const disconnect = () => {
  manualDisconnect = true          // 标记为手动断开
  shouldReconnect = false          // 禁止自动重连
  
  // 清除重连定时器
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  
  // 关闭 SSE 连接
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  
  // 重置状态
  isConnected.value = false
  connectionInfo.value = null
  reconnectAttempts = 0
  reconnectInfo.value = {
    attempts: 0,
    maxAttempts: maxReconnectAttempts,
    nextDelay: 0
  }
  streamBuffer.value = ''  // 清空流式缓冲区
}

/**
 * 发送消息
 */
const sendMessage = async () => {
  if (!targetUserId.value || !messageContent.value) return

  sending.value = true
  sendResult.value = null

  try {
    // 调用后端 API 发送消息
    const response = await axios.post('http://localhost:8080/api/sse/send', {
      userId: targetUserId.value,
      eventName: eventName.value || null,
      message: messageContent.value
    })

    sendResult.value = response.data
  } catch (error) {
    console.error('Failed to send message:', error)
    sendResult.value = {
      success: false,
      message: '发送失败: ' + (error.response?.data?.message || error.message)
    }
  } finally {
    sending.value = false
  }
}

/**
 * 模拟 AI 流式输出
 * 只发送一次请求，后端通过 SSE 逐字推送
 */
const sendStreamMessage = async () => {
  if (!targetUserId.value) return

  streaming.value = true
  sendResult.value = null
  streamBuffer.value = ''  // 清空流式缓冲区

  try {
    // 只发送一次请求，触发后端流式输出
    const response = await axios.post('http://localhost:8080/api/sse/stream', {
      userId: targetUserId.value
    })

    sendResult.value = response.data
  } catch (error) {
    console.error('Failed to send stream message:', error)
    sendResult.value = {
      success: false,
      message: '流式输出失败: ' + (error.response?.data?.message || error.message)
    }
  } finally {
    // 不在这里设置 streaming.value = false，等待流式输出完成
    // 可以通过监听流式输出来判断是否完成
  }
}

/**
 * 查询连接状态
 */
const checkStatus = async () => {
  if (!userId.value) return

  try {
    const response = await axios.get(`http://localhost:8080/api/sse/status/${userId.value}`)
    connectionInfo.value = response.data
  } catch (error) {
    console.error('Failed to check status:', error)
  }
}

/**
 * 清空消息列表
 */
const clearMessages = () => {
  messages.value = []
  streamBuffer.value = ''  // 清空流式缓冲区
}

/**
 * 格式化时间戳
 * @param timestamp 时间戳
 * @return 格式化后的时间字符串
 */
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour12: false }) + '.' + 
         date.getMilliseconds().toString().padStart(3, '0')
}

/**
 * 计算重连延迟时间（指数退避算法）
 * @return 延迟时间（毫秒）
 */
const calculateReconnectDelay = () => {
  // 计算指数退避延迟：min(1000 * 2^attempts, 30000)
  const delay = Math.min(
    baseReconnectDelay * Math.pow(2, reconnectAttempts),
    maxReconnectDelay
  )
  // 添加随机抖动（±15%），避免所有客户端同时重连
  const jitter = Math.random() * 0.3 * delay
  return Math.floor(delay + jitter)
}

/**
 * 调度重连任务
 */
const scheduleReconnect = () => {
  // 检查是否超过最大重连次数
  if (reconnectAttempts >= maxReconnectAttempts) {
    console.error('Max reconnect attempts reached, giving up')
    shouldReconnect = false
    
    // 更新重连信息显示为失败状态
    reconnectInfo.value = {
      attempts: reconnectAttempts,
      maxAttempts: maxReconnectAttempts,
      nextDelay: 0,
      failed: true
    }
    return
  }

  // 计算延迟时间
  const delay = calculateReconnectDelay()
  
  // 更新重连信息显示（在重连前显示）
  reconnectInfo.value = {
    attempts: reconnectAttempts + 1,  // 显示即将进行的重连次数
    maxAttempts: maxReconnectAttempts,
    nextDelay: delay,
    failed: false
  }
  
  console.log(`Reconnecting in ${delay}ms (attempt ${reconnectAttempts + 1}/${maxReconnectAttempts})`)
  
  // 设置定时器，延迟后重新连接
  reconnectTimer = setTimeout(() => {
    reconnectAttempts++  // 实际增加重连次数
    console.log(`Attempting to reconnect (attempt ${reconnectAttempts})`)
    connect(true)  // 传递 isReconnect = true，避免重置重连次数
  }, delay)
}

/**
 * 重置重连状态
 */
const resetReconnectState = () => {
  reconnectAttempts = 0
  shouldReconnect = true
  manualDisconnect = false
  
  // 清除重连定时器
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

onMounted(() => {
  targetUserId.value = userId.value
})

onUnmounted(() => {
  disconnect()
})
</script>
