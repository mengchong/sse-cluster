# CORS 跨域配置说明

## 问题背景

在开发环境中，前端运行在 `http://localhost:3000`，后端运行在 `http://localhost:8080`，浏览器会因为同源策略（Same-Origin Policy）阻止跨域请求。

## 解决方案

项目采用了双重 CORS 配置策略：

### 1. 后端全局 CORS 配置

**文件位置：** [CorsConfig.java](file:///d:/aicode/面试准备/SSE/backend/src/main/java/com/example/sse/config/CorsConfig.java)

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")              // 允许所有来源
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")  // 允许的 HTTP 方法
                .allowedHeaders("*")                    // 允许所有请求头
                .allowCredentials(true)                 // 允许携带凭证
                .maxAge(3600);                          // 预检缓存 1 小时
    }
}
```

### 2. 控制器级别 CORS 注解

**文件位置：** [SseController.java](file:///d:/aicode/面试准备/SSE/backend/src/main/java/com/example/sse/controller/SseController.java)

```java
@RestController
@RequestMapping("/api/sse")
@CrossOrigin(origins = "*", maxAge = 3600)  // 控制器级别 CORS 配置
public class SseController {
    // ...
}
```

### 3. 前端代理配置

**文件位置：** [vite.config.js](file:///d:/aicode/面试准备/SSE/frontend/vite.config.js)

```javascript
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // 后端地址
        changeOrigin: true                // 修改请求头的 Origin
      }
    }
  }
})
```

## 配置说明

### 允许的请求

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `allowedOriginPatterns` | `*` | 允许所有来源（开发环境） |
| `allowedMethods` | GET, POST, PUT, DELETE, OPTIONS | 允许的 HTTP 方法 |
| `allowedHeaders` | `*` | 允许所有请求头 |
| `allowCredentials` | `true` | 允许携带 Cookie 和 Authorization |
| `maxAge` | `3600` | 预检请求缓存时间（秒） |

### 生产环境建议

在生产环境中，**不建议使用 `*` 允许所有来源**，应该指定具体的前端域名：

```java
@CrossOrigin(
    origins = "https://your-frontend-domain.com",
    maxAge = 3600
)
```

或者使用配置文件：

```yaml
cors:
  allowed-origins:
    - https://your-frontend-domain.com
    - https://www.your-frontend-domain.com
```

## 工作原理

### 开发环境（使用代理）

```
前端请求 → Vite 代理 → 后端 API
   ↓
   修改 Origin 头
   ↓
   后端处理请求
```

### 生产环境（直接请求）

```
前端请求 → 浏览器检查 CORS
   ↓
   后端返回 CORS 头
   ↓
   浏览器允许请求
   ↓
   后端处理请求
```

## 验证 CORS 配置

### 1. 检查响应头

使用浏览器开发者工具查看响应头：

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: *
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 3600
```

### 2. 测试跨域请求

```bash
# 使用 curl 测试
curl -H "Origin: http://localhost:3000" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: Content-Type" \
     -X OPTIONS \
     http://localhost:8080/api/sse/send
```

## 常见问题

### 1. 预检请求失败

**问题：** OPTIONS 请求返回 403 或 404

**原因：** CORS 配置未生效或路径不匹配

**解决：** 检查 `CorsConfig` 的路径映射是否正确

### 2. Cookie 未发送

**问题：** 请求中不包含 Cookie

**原因：** `allowCredentials` 未设置为 `true`

**解决：** 确保后端和前端都配置了 `withCredentials: true`

### 3. SSE 连接被阻止

**问题：** SSE 连接建立失败

**原因：** SSE 使用 EventSource，不支持自定义请求头

**解决：** SSE 请求不需要 CORS，因为 EventSource 不支持自定义头

## 安全建议

### 开发环境
- 使用 `*` 允许所有来源
- 允许所有请求头
- 启用凭证传递

### 生产环境
- 指定具体的前端域名
- 限制允许的 HTTP 方法
- 限制允许的请求头
- 启用 HTTPS
- 设置合理的预检缓存时间

## 总结

项目已完整配置 CORS 支持：

✅ **全局配置** - CorsConfig.java
✅ **控制器注解** - @CrossOrigin
✅ **前端代理** - Vite proxy
✅ **开发环境** - 允许所有来源
✅ **生产环境** - 可配置具体域名

确保前后端可以正常通信！
