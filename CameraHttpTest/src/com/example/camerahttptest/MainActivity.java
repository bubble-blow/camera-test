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
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
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
    private TextView fpsText;
    private TextView resolutionText;
    private FrameIntervalHistogramView histogramView;
    private TextView errorText;

    private volatile boolean serverRunning;
    private ServerSocket serverSocket;
    private Thread serverThread;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private final List<ImageReader> imageReaders = new ArrayList<ImageReader>();
    private long lastFrameTimestampNs = -1L;
    private final Deque<Long> recentFrameIntervalsNs = new ArrayDeque<Long>();
    private static final int FRAME_WINDOW_SIZE = 10;
    private static final double MIN_HW_RATIO = 0.73;
    private static final double MAX_HW_RATIO = 0.77;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverStatusText = (TextView) findViewById(R.id.serverStatus);
        frameIntervalText = (TextView) findViewById(R.id.frameIntervalText);
        fpsText = (TextView) findViewById(R.id.fpsText);
        resolutionText = (TextView) findViewById(R.id.resolutionText);
        histogramView = (FrameIntervalHistogramView) findViewById(R.id.histogramView);
        errorText = (TextView) findViewById(R.id.errorText);
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
        DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
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


    private void showCameraErrorMessage(final String message) {
        Log.e(TAG, message);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                errorText.setText("Camera error: " + message);
            }
        });
    }

    private void clearCameraErrorMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                errorText.setText("Camera error: none");
            }
        });
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
            clearCameraErrorMessage();
            clearHistogramData();
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
                                onFrameArrived(image.getTimestamp(), image.getWidth(), image.getHeight());
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

            final int minExposureCompensation = getMinExposureCompensation(cameraId);

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
                                            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, minExposureCompensation);
                                            for (ImageReader reader : imageReaders) {
                                                builder.addTarget(reader.getSurface());
                                            }
                                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                        } catch (CameraAccessException e) {
                                            showCameraErrorMessage("setRepeatingRequest failed: " + e.getMessage());
                                        }
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    showCameraErrorMessage("Capture session configure failed");
                                }
                            }, cameraHandler);
                        } catch (CameraAccessException e) {
                            showCameraErrorMessage("createCaptureSession failed: " + e.getMessage());
                        }
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    showCameraErrorMessage("Camera disconnected");
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    showCameraErrorMessage("Camera device error code=" + error);
                    camera.close();
                }
            }, cameraHandler);

            result.put("ok", true);
            result.put("message", "openCamera command submitted");
        } catch (Exception e) {
            safeCloseReaders();
            String msg = "openCamera failed: " + e.getMessage();
            showCameraErrorMessage(msg);
            putError(result, msg);
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
        lastFrameTimestampNs = -1L;
        recentFrameIntervalsNs.clear();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameIntervalText.setText("Frame interval: N/A");
                fpsText.setText("Avg FPS(10): N/A");
                resolutionText.setText("Image size: N/A");
                if (histogramView != null) {
                    histogramView.clearData();
                }
            }
        });
    }

    private void onFrameArrived(long imageTimestampNs, int width, int height) {
        final String intervalText;
        final String fpsLineText;
        final String sizeText = "Image size: " + width + "x" + height;

        if (lastFrameTimestampNs > 0) {
            long diffNs = imageTimestampNs - lastFrameTimestampNs;
            if (diffNs < 0) {
                diffNs = 0;
            }
            recentFrameIntervalsNs.addLast(diffNs);
            while (recentFrameIntervalsNs.size() > FRAME_WINDOW_SIZE) {
                recentFrameIntervalsNs.removeFirst();
            }

            double sumNs = 0;
            for (Long intervalNs : recentFrameIntervalsNs) {
                sumNs += intervalNs;
            }
            double avgIntervalNs = sumNs / recentFrameIntervalsNs.size();
            double avgFps = avgIntervalNs > 0 ? (1000000000.0 / avgIntervalNs) : 0;
            double diffMs = diffNs / 1000000.0;

            intervalText = String.format(Locale.US, "Frame interval: %.3f ms", diffMs);
            fpsLineText = String.format(Locale.US, "Avg FPS(10): %.2f", avgFps);
            addHistogramSample(diffMs);
        } else {
            intervalText = "Frame interval: collecting...";
            fpsLineText = "Avg FPS(10): collecting...";
        }
        lastFrameTimestampNs = imageTimestampNs;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameIntervalText.setText(intervalText);
                fpsText.setText(fpsLineText);
                resolutionText.setText(sizeText);
            }
        });
    }

    private void addHistogramSample(double intervalMs) {
        final int bucketMs = (int) Math.round(intervalMs);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (histogramView != null) {
                    histogramView.addIntervalMs(bucketMs);
                }
            }
        });
    }

    private void clearHistogramData() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (histogramView != null) {
                    histogramView.clearData();
                }
            }
        });
    }

    private int getMinExposureCompensation(String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (range != null) {
                return range.getLower();
            }
        } catch (Exception e) {
            showCameraErrorMessage("Failed to query AE compensation range: " + e.getMessage());
        }
        return 0;
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

        if ("YV12".equals(normalized)) return ImageFormat.YV12;
        if ("YUV_420_888".equals(normalized)) return ImageFormat.YUV_420_888;
        if ("NV21".equals(normalized)) return ImageFormat.NV21;
        if ("NV16".equals(normalized)) return ImageFormat.NV16;
        if ("JPEG".equals(normalized)) return ImageFormat.JPEG;
        if ("YUY2".equals(normalized)) return ImageFormat.YUY2;
        if ("Y8".equals(normalized)) return ImageFormat.Y8;
        if ("RAW_SENSOR".equals(normalized)) return ImageFormat.RAW_SENSOR;
        if ("RAW_PRIVATE".equals(normalized)) return ImageFormat.RAW_PRIVATE;
        if ("RAW10".equals(normalized)) return ImageFormat.RAW10;
        if ("DEPTH16".equals(normalized)) return ImageFormat.DEPTH16;
        if ("DEPTH_POINT_CLOUD".equals(normalized)) return ImageFormat.DEPTH_POINT_CLOUD;
        if ("DEPTH_JPEG".equals(normalized)) return ImageFormat.DEPTH_JPEG;
        if ("PRIVATE".equals(normalized)) return ImageFormat.PRIVATE;
        if ("HEIC".equals(normalized)) return ImageFormat.HEIC;

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
                                if (isInHeightWidthRatioRange(size.getWidth(), size.getHeight())) {
                                    JSONObject sizeObj = new JSONObject();
                                    sizeObj.put("width", size.getWidth());
                                    sizeObj.put("height", size.getHeight());
                                    sizesArray.put(sizeObj);
                                }
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

    private static boolean isInHeightWidthRatioRange(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        double ratio = (double) height / (double) width;
        return ratio >= MIN_HW_RATIO && ratio <= MAX_HW_RATIO;
    }

    public static String imageFormatToName(int format) {
        switch (format) {
            case ImageFormat.YV12:
                return "YV12";
            case ImageFormat.YUV_420_888:
                return "YUV_420_888";
            case ImageFormat.NV21:
                return "NV21";
            case ImageFormat.NV16:
                return "NV16";
            case ImageFormat.JPEG:
                return "JPEG";
            case ImageFormat.YUY2:
                return "YUY2";
            case ImageFormat.Y8:
                return "Y8";
            case ImageFormat.RAW_SENSOR:
                return "RAW_SENSOR";
            case ImageFormat.RAW_PRIVATE:
                return "RAW_PRIVATE";
            case ImageFormat.RAW10:
                return "RAW10";
            case ImageFormat.DEPTH16:
                return "DEPTH16";
            case ImageFormat.DEPTH_POINT_CLOUD:
                return "DEPTH_POINT_CLOUD";
            case ImageFormat.DEPTH_JPEG:
                return "DEPTH_JPEG";
            case ImageFormat.PRIVATE:
                return "PRIVATE";
            case ImageFormat.HEIC:
                return "HEIC";
            default:
                return "UNKNOWN_" + format;
        }
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
