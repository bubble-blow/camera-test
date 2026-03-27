# CameraHttpTest (Eclipse Android Project)

该项目是 Eclipse + ADT 结构的 Android 工程，`targetSdkVersion=29`。

## 功能

- MainActivity 启动时创建基于 Android 内置 `org.apache.http`（HttpCore 风格）的本地 HTTP 服务器（默认端口 `8080`）。
- MainActivity 销毁时自动关闭 HTTP 服务器和摄像头资源。
- 监听 JSON 请求：
  - `POST /open`：打开指定 cameraId，按 `readers` 参数创建一个或多个 `ImageReader`。
  - `POST /close`：关闭摄像头。
  - `GET /capabilities`：获取各摄像头支持的输出格式与分辨率列表（仅保留 `height/width` 在 `0.73~0.77` 的分辨率）。
- `ImageReader` 只用于测试：在 `onImageAvailable` 中仅 `acquireLatestImage()` 后立即 `close()`，不输出图像；帧间隔计算使用 `Image.getTimestamp()`。
- Activity 上实时显示帧间隔（毫秒），并基于最近 10 个帧间隔显示平均 FPS；发生相机错误时会在界面上显示错误信息。打开相机时会将曝光补偿设为相机支持范围内的最小值。
- `assets/index.html` 提供前端页面，用于输入 cameraId、动态增减 `ImageReader` 参数并发送打开/关闭请求（通过相对路径直接请求当前站点），并展示 `/capabilities` 返回的能力列表（不影响表单）。

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

- `format` 支持数字值或名称：`YV12`、`YUV_420_888`、`NV21`、`NV16`、`JPEG`、`YUY2`、`Y8`、`RAW_SENSOR`、`RAW_PRIVATE`、`RAW10`、`DEPTH16`、`DEPTH_POINT_CLOUD`、`DEPTH_JPEG`、`PRIVATE`、`HEIC`。

## 说明

- 服务器实现使用 `ServerSocket + org.apache.http.protocol.HttpService`，避免 `ServerBootstrap` 依赖。
- 项目配置为 `target=android-29`，并在 `project.properties` 中指定 `sdk.buildtools=25.0.1`。
