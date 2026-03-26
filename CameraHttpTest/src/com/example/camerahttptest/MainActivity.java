package com.example.camerahttptest;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.TextView;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "CameraHttpTest";
    private static final int SERVER_PORT = 8080;

    private TextView serverStatusText;
    private TextView frameIntervalText;

    private HttpServer httpServer;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private final List<ImageReader> imageReaders = new ArrayList<ImageReader>();
    private long lastFrameTimestampMs = -1L;

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
        if (httpServer != null) {
            return;
        }

        httpServer = ServerBootstrap.bootstrap()
                .setListenerPort(SERVER_PORT)
                .registerHandler("/", new IndexHandler())
                .registerHandler("/open", new OpenCameraHandler())
                .registerHandler("/close", new CloseCameraHandler())
                .create();

        try {
            httpServer.start();
            updateServerStatus("Server: running on port " + SERVER_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            updateServerStatus("Server: failed to start - " + e.getMessage());
        }
    }

    private synchronized void stopWebServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            updateServerStatus("Server: stopped");
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

            final List<android.view.Surface> surfaces = new ArrayList<android.view.Surface>();
            for (ReaderSpec spec : specs) {
                ImageReader reader = ImageReader.newInstance(spec.width, spec.height, spec.format, 2);
                reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = null;
                        try {
                            image = reader.acquireLatestImage();
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
        } catch (CameraAccessException e) {
            safeCloseReaders();
            putError(result, "Camera access error: " + e.getMessage());
        } catch (JSONException e) {
            putError(result, "JSON error: " + e.getMessage());
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameIntervalText.setText("Frame interval: N/A");
            }
        });
    }

    private void onFrameArrived() {
        long now = System.currentTimeMillis();
        final String text;
        if (lastFrameTimestampMs > 0) {
            long diff = now - lastFrameTimestampMs;
            text = "Frame interval: " + diff + " ms";
        } else {
            text = "Frame interval: collecting...";
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
            // no-op
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
        if ("YUV_420_888".equalsIgnoreCase(formatName)) {
            return ImageFormat.YUV_420_888;
        }
        if ("JPEG".equalsIgnoreCase(formatName)) {
            return ImageFormat.JPEG;
        }
        throw new JSONException("Unsupported format: " + formatName);
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
            response.setEntity(new StringEntity(html.toString(), ContentType.create("text/html", "UTF-8")));
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
            response.setEntity(new StringEntity(result.toString(), ContentType.APPLICATION_JSON));
        }
    }

    private class CloseCameraHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
            JSONObject result = closeCameraCommand();
            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new StringEntity(result.toString(), ContentType.APPLICATION_JSON));
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
