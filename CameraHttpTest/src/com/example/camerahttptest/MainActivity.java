package com.example.camerahttptest;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.HttpServerConnection;
import org.apache.http.HttpStatus;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "CameraHttpTest";
    private static final int SERVER_PORT = 8080;

    private TextView serverStatusText;
    private TextView frameIntervalText;

    private volatile boolean serverRunning;
    private ServerSocket serverSocket;
    private Thread serverThread;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private final List<ImageReader> imageReaders = new ArrayList<ImageReader>();
    private long lastFrameTimestampMs = -1L;
    private final Deque<Long> recentFrameIntervalsMs = new ArrayDeque<Long>();
    private static final int FRAME_WINDOW_SIZE = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverStatusText = (TextView) findViewById(R.id.serverStatus);
        frameIntervalText = (TextView) findViewById(R.id.frameIntervalText);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        startCameraThread();
        startWebServer();
    }

    @Override
    protected void onDestroy() {
        stopWebServer();
        closeCamera();
        stopCameraThread();
        super.onDestroy();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("camera-bg");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private synchronized void startWebServer() {
        if (serverRunning) {
            return;
        }

        serverRunning = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runHttpServerLoop();
            }
        }, "http-server");
        serverThread.start();
        updateServerStatus("Server: starting on port " + SERVER_PORT);
    }

    private synchronized void stopWebServer() {
        serverRunning = false;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "close server socket failed", e);
            }
            serverSocket = null;
        }

        if (serverThread != null) {
            serverThread.interrupt();
            try {
                serverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }

        updateServerStatus("Server: stopped");
    }

    private void runHttpServerLoop() {
        try {
            serverSocket = new ServerSocket(SERVER_PORT);
            updateServerStatus("Server: running on port " + SERVER_PORT);

            final HttpService httpService = createHttpService();

            while (serverRunning) {
                Socket socket = null;
                try {
                    socket = serverSocket.accept();
                    handleClientSocket(httpService, socket);
                } catch (IOException e) {
                    if (serverRunning) {
                        Log.e(TAG, "accept failed", e);
                    }
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (IOException ignore) {
                            // ignore
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "start server failed", e);
            updateServerStatus("Server: failed - " + e.getMessage());
        }
    }

    private HttpService createHttpService() {
        BasicHttpProcessor httpProcessor = new BasicHttpProcessor();
        httpProcessor.addInterceptor(new ResponseDate());
        httpProcessor.addInterceptor(new ResponseServer());
        httpProcessor.addInterceptor(new ResponseContent());
        httpProcessor.addInterceptor(new ResponseConnControl());

        HttpRequestHandlerRegistry registry = new HttpRequestHandlerRegistry();
        registry.register("/", new IndexHandler());
        registry.register("/open", new OpenCameraHandler());
        registry.register("/close", new CloseCameraHandler());
        registry.register("/capabilities", new CapabilitiesHandler());

        ConnectionReuseStrategy reuseStrategy = new DefaultConnectionReuseStrategy();
        HttpResponseFactory responseFactory = new DefaultHttpResponseFactory();

        HttpService httpService = new HttpService(httpProcessor, reuseStrategy, responseFactory);
        httpService.setHandlerResolver(registry);
        return httpService;
    }

    private void handleClientSocket(HttpService httpService, Socket socket) {
        HttpServerConnection connection = new DefaultHttpServerConnection();
        HttpContext context = new BasicHttpContext(null);
        try {
            connection.bind(socket, new BasicHttpParams());
            httpService.handleRequest(connection, context);
        } catch (Exception e) {
            Log.e(TAG, "handle request failed", e);
        } finally {
            try {
                connection.shutdown();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private void updateServerStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                serverStatusText.setText(text);
            }
        });
    }

    private synchronized JSONObject openCamera(String cameraId, List<ReaderSpec> specs) {
        JSONObject result = new JSONObject();
        try {
            closeCamera();

            final List<Surface> surfaces = new ArrayList<Surface>();
            for (ReaderSpec spec : specs) {
                ImageReader reader = ImageReader.newInstance(spec.width, spec.height, spec.format, 2);
                reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader imageReader) {
                        Image image = null;
                        try {
                            image = imageReader.acquireLatestImage();
                            if (image != null) {
                                onFrameArrived();
                            }
                        } finally {
                            if (image != null) {
                                image.close();
                            }
                        }
                    }
                }, cameraHandler);
                imageReaders.add(reader);
                surfaces.add(reader.getSurface());
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (MainActivity.this) {
                        cameraDevice = camera;
                        try {
                            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    synchronized (MainActivity.this) {
                                        captureSession = session;
                                        try {
                                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            for (ImageReader reader : imageReaders) {
                                                builder.addTarget(reader.getSurface());
                                            }
                                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                        } catch (CameraAccessException e) {
                                            Log.e(TAG, "setRepeatingRequest failed", e);
                                        }
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "Capture session configure failed");
                                }
                            }, cameraHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "createCaptureSession failed", e);
                        }
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                }
            }, cameraHandler);

            result.put("ok", true);
            result.put("message", "openCamera command submitted");
        } catch (Exception e) {
            safeCloseReaders();
            putError(result, "openCamera failed: " + e.getMessage());
        }
        return result;
    }

    private synchronized JSONObject closeCameraCommand() {
        JSONObject result = new JSONObject();
        closeCamera();
        try {
            result.put("ok", true);
            result.put("message", "camera closed");
        } catch (JSONException e) {
            Log.e(TAG, "build close response failed", e);
        }
        return result;
    }

    private synchronized void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        safeCloseReaders();
    }

    private void safeCloseReaders() {
        for (ImageReader reader : imageReaders) {
            reader.close();
        }
        imageReaders.clear();
        lastFrameTimestampMs = -1L;
        recentFrameIntervalsMs.clear();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameIntervalText.setText("Frame interval: N/A, Avg FPS(10): N/A");
            }
        });
    }

    private void onFrameArrived() {
        long now = System.currentTimeMillis();
        final String text;
        if (lastFrameTimestampMs > 0) {
            long diff = now - lastFrameTimestampMs;
            recentFrameIntervalsMs.addLast(diff);
            while (recentFrameIntervalsMs.size() > FRAME_WINDOW_SIZE) {
                recentFrameIntervalsMs.removeFirst();
            }

            double sum = 0;
            for (Long intervalMs : recentFrameIntervalsMs) {
                sum += intervalMs;
            }
            double avgIntervalMs = sum / recentFrameIntervalsMs.size();
            double avgFps = avgIntervalMs > 0 ? (1000.0 / avgIntervalMs) : 0;

            text = String.format(Locale.US,
                    "Frame interval: %d ms, Avg FPS(10): %.2f",
                    diff,
                    avgFps);
        } else {
            text = "Frame interval: collecting..., Avg FPS(10): collecting...";
        }
        lastFrameTimestampMs = now;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameIntervalText.setText(text);
            }
        });
    }

    private static void putError(JSONObject obj, String msg) {
        try {
            obj.put("ok", false);
            obj.put("message", msg);
        } catch (JSONException ignore) {
            // ignore
        }
    }

    private static String readEntityBody(HttpRequest request) throws IOException, HttpException {
        if (!(request instanceof HttpEntityEnclosingRequest)) {
            throw new HttpException("Request does not include body");
        }
        InputStream in = ((HttpEntityEnclosingRequest) request).getEntity().getContent();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        return builder.toString();
    }

    private static int parseImageFormat(Object formatValue) throws JSONException {
        if (formatValue instanceof Number) {
            return ((Number) formatValue).intValue();
        }

        String formatName = String.valueOf(formatValue);
        String normalized = formatName.trim().toUpperCase(Locale.US)
                .replace("IMAGEFORMAT.", "")
                .replace('-', '_')
                .replace(' ', '_');

        if ("UNKNOWN".equals(normalized)) {
            return ImageFormat.UNKNOWN;
        }
        if ("RGB_565".equals(normalized)) {
            return ImageFormat.RGB_565;
        }
        if ("NV16".equals(normalized)) {
            return ImageFormat.NV16;
        }
        if ("NV21".equals(normalized)) {
            return ImageFormat.NV21;
        }
        if ("YUY2".equals(normalized)) {
            return ImageFormat.YUY2;
        }
        if ("YV12".equals(normalized)) {
            return ImageFormat.YV12;
        }
        if ("JPEG".equals(normalized)) {
            return ImageFormat.JPEG;
        }
        if ("YUV_420_888".equals(normalized)) {
            return ImageFormat.YUV_420_888;
        }
        if ("RAW_SENSOR".equals(normalized)) {
            return ImageFormat.RAW_SENSOR;
        }
        if ("RAW10".equals(normalized)) {
            return ImageFormat.RAW10;
        }
        if ("RAW12".equals(normalized)) {
            return ImageFormat.RAW12;
        }
        if ("DEPTH16".equals(normalized)) {
            return ImageFormat.DEPTH16;
        }

        throw new JSONException("Unsupported format: " + formatName);
    }


    private synchronized JSONObject getCameraCapabilities() {
        JSONObject result = new JSONObject();
        JSONArray cameraArray = new JSONArray();

        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            Arrays.sort(cameraIds);

            for (String cameraId : cameraIds) {
                JSONObject cameraObj = new JSONObject();
                cameraObj.put("cameraId", cameraId);

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                android.hardware.camera2.params.StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                JSONArray streams = new JSONArray();
                if (map != null) {
                    int[] formats = map.getOutputFormats();
                    Arrays.sort(formats);
                    for (int format : formats) {
                        JSONObject formatObj = new JSONObject();
                        formatObj.put("format", format);
                        formatObj.put("formatName", imageFormatToName(format));

                        JSONArray sizesArray = new JSONArray();
                        Size[] sizes = map.getOutputSizes(format);
                        if (sizes != null) {
                            for (Size size : sizes) {
                                JSONObject sizeObj = new JSONObject();
                                sizeObj.put("width", size.getWidth());
                                sizeObj.put("height", size.getHeight());
                                sizesArray.put(sizeObj);
                            }
                        }

                        formatObj.put("sizes", sizesArray);
                        streams.put(formatObj);
                    }
                }

                cameraObj.put("streams", streams);
                cameraArray.put(cameraObj);
            }

            result.put("ok", true);
            result.put("cameras", cameraArray);
        } catch (Exception e) {
            putError(result, "Failed to load capabilities: " + e.getMessage());
        }

        return result;
    }

    private static String imageFormatToName(int format) {
        if (format == ImageFormat.UNKNOWN) return "UNKNOWN";
        if (format == ImageFormat.RGB_565) return "RGB_565";
        if (format == ImageFormat.NV16) return "NV16";
        if (format == ImageFormat.NV21) return "NV21";
        if (format == ImageFormat.YUY2) return "YUY2";
        if (format == ImageFormat.YV12) return "YV12";
        if (format == ImageFormat.JPEG) return "JPEG";
        if (format == ImageFormat.YUV_420_888) return "YUV_420_888";
        if (format == ImageFormat.RAW_SENSOR) return "RAW_SENSOR";
        if (format == ImageFormat.RAW10) return "RAW10";
        if (format == ImageFormat.RAW12) return "RAW12";
        if (format == ImageFormat.DEPTH16) return "DEPTH16";
        return "UNKNOWN_" + format;
    }

    private class IndexHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            AssetManager assetManager = getAssets();
            InputStream in = assetManager.open("index.html");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append('\n');
            }
            response.setStatusCode(HttpStatus.SC_OK);
            response.setHeader("Content-Type", "text/html; charset=UTF-8");
            response.setEntity(new StringEntity(html.toString(), "UTF-8"));
        }
    }

    private class OpenCameraHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            JSONObject result;
            try {
                String body = readEntityBody(request);
                JSONObject payload = new JSONObject(body);

                String cameraId = payload.optString("cameraId", "0");
                JSONArray readers = payload.optJSONArray("readers");
                List<ReaderSpec> specs = new ArrayList<ReaderSpec>();

                if (readers != null && readers.length() > 0) {
                    for (int i = 0; i < readers.length(); i++) {
                        JSONObject item = readers.getJSONObject(i);
                        int width = item.getInt("width");
                        int height = item.getInt("height");
                        int format = parseImageFormat(item.get("format"));
                        specs.add(new ReaderSpec(width, height, format));
                    }
                } else {
                    specs.add(new ReaderSpec(640, 480, ImageFormat.YUV_420_888));
                }

                result = openCamera(cameraId, specs);
            } catch (Exception e) {
                result = new JSONObject();
                putError(result, "Invalid request: " + e.getMessage());
            }

            response.setStatusCode(HttpStatus.SC_OK);
            response.setHeader("Content-Type", "application/json; charset=UTF-8");
            response.setEntity(new StringEntity(result.toString(), "UTF-8"));
        }
    }

    private class CloseCameraHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            JSONObject result = closeCameraCommand();
            response.setStatusCode(HttpStatus.SC_OK);
            response.setHeader("Content-Type", "application/json; charset=UTF-8");
            response.setEntity(new StringEntity(result.toString(), "UTF-8"));
        }
    }


    private class CapabilitiesHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            JSONObject result = getCameraCapabilities();
            response.setStatusCode(HttpStatus.SC_OK);
            response.setHeader("Content-Type", "application/json; charset=UTF-8");
            response.setEntity(new StringEntity(result.toString(), "UTF-8"));
        }
    }

    private static class ReaderSpec {
        final int width;
        final int height;
        final int format;

        ReaderSpec(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }
}
