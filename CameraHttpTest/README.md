# CameraHttpTest (Eclipse Android Project)

该项目是 Eclipse + ADT 结构的 Android 工程，`targetSdkVersion=21`。

## 功能

- MainActivity 启动时创建基于 Android 内置 `org.apache.http`（HttpCore 风格）的本地 HTTP 服务器（默认端口 `8080`）。
- MainActivity 销毁时自动关闭 HTTP 服务器和摄像头资源。
- 监听 JSON 请求：
  - `POST /open`：打开指定 cameraId，按 `readers` 参数创建一个或多个 `ImageReader`。
  - `POST /close`：关闭摄像头。
- `ImageReader` 只用于测试：在 `onImageAvailable` 中仅 `acquireLatestImage()` 后立即 `close()`，不输出图像。
- Activity 上实时显示帧间隔（毫秒），并基于最近 10 个帧间隔显示平均 FPS。
- `assets/index.html` 提供前端页面，用于输入 cameraId、动态增减 `ImageReader` 参数并发送打开/关闭请求（通过相对路径直接请求当前站点）。

## 请求示例

### 打开摄像头

```json
{
  "cameraId": "0",
  "readers": [
    {"width": 640, "height": 480, "format": "YUV_420_888"},
    {"width": 1280, "height": 720, "format": "JPEG"}
  ]
}
```

### 关闭摄像头

```json
{}
```

## 说明

- 服务器实现使用 `ServerSocket + org.apache.http.protocol.HttpService`，避免 `ServerBootstrap` 依赖。
- `targetSdkVersion=21` 场景下可直接使用内置 `org.apache.http` 相关 API。
