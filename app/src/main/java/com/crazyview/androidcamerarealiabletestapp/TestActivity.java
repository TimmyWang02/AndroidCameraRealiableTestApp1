package com.crazyview.androidcamerarealiabletestapp;

import static java.lang.Thread.sleep;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

public class TestActivity extends AppCompatActivity {

    private static final String TAG = "TestActivity";

    private Button btnStart, btnStop;
    private TextView tvSuccessfulPhotos;
    private Camera2Helper cameraHelper;
    private AutoFitTextureView textView;
    private int maxStorage;
    private int testTimes;
    private int testCounter = 0;
    private File photoDirectory;
    private File configFile;
    private boolean isStop = false;
    private boolean isZero = false;
    private final ConditionVariable conditionVariable = new ConditionVariable();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> {
//            stopCamera();
            onBackPressed();
        });

        textView = findViewById(R.id.texture);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvSuccessfulPhotos = findViewById(R.id.tv_successful_photos);

        photoDirectory = new File(Environment.getExternalStorageDirectory() + "/Download/hello");
        configFile = new File(getExternalFilesDir(null), "config.json");

        if (!hasPermissions()) {
            requestPermissions();
        }

        btnStart.setOnClickListener(v -> {
            try {
                resetPhotoCount();  // 重置 successful_photos 为 0
                loadConfig();
                startCamera();
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error resetting photo count before start", e);
                showErrorToast("Error resetting photo count before start: " + e.getMessage());
            }
        });

        btnStop.setOnClickListener(v -> stopCamera());
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    private void startCamera() {
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        deleteAllPhotos();
        Log.d(TAG, "startCamera");
        cameraHelper = Camera2Helper.getInstance(this, textView, null, "0");
        cameraHelper.setAfterDoListener(new Camera2Helper.AfterDoListener() {
            @Override
            public void onAfterPreviewBack() {
                Log.d(TAG, "onAfterPreviewBack");
                conditionVariable.open();
            }

            @Override
            public void onAfterTakePicture() {
                Log.d(TAG, "onAfterTakePicture");
                conditionVariable.open();
            }
        });
        try {
            cameraHelper.startCameraPreView();
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera preview", e);
            showErrorToast("Error starting camera preview: " + e.getMessage());
        }

        isStop = false;
        Log.d("isStop", "isStop: " + isStop);
        testCounter = 0;  // 初始化计数器
        new Thread(() -> {
            // 等待预览成功回调
            conditionVariable.block();
            conditionVariable.close();

            while (!isStop) {
                try {
                    // 每次拍照前创建新的文件路径
                    String fileName = "photo" + (testCounter + 1) + ".jpg";
                    File file = new File(photoDirectory, fileName);

                    cameraHelper.takePicture(file);
                    conditionVariable.block();
                    conditionVariable.close();
                    sleep(1000);

                    // 检查文件是否存在
                    if (file.exists()) {
                        updateConfig(1);
                        testCounter++;
                        Log.i("aaaaa", "Photo taken: " + testCounter);
                    } else {
                        Log.e(TAG, "Photo file not found: " + fileName);
                        showErrorToast("Photo error: file not found - " + fileName);
                    }

                    if (testCounter >= testTimes && !isZero) {
                        isStop = true;
                    }

                    if (testCounter % maxStorage == 0) {
                        deleteAllPhotos();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during camera operation", e);
                    showErrorToast("Error during camera operation: " + e.getMessage());
                }
            }

            runOnUiThread(() -> {
                try {
                    cameraHelper.onDestroyHelper();
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                } catch (Exception e) {
                    Log.e(TAG, "Error destroying camera helper", e);
                    showErrorToast("Error destroying camera helper: " + e.getMessage());
                }
                // 显示停止拍照的Toast
                Toast.makeText(this, "Camera stopped after " + testCounter + " photos", Toast.LENGTH_SHORT).show();
            });
        }).start();

        Toast.makeText(this, "Camera started", Toast.LENGTH_SHORT).show();
    }

    private void loadConfig() {
        try {
            if (!configFile.exists()) {
                configFile.createNewFile();
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("successful_photos", 0);
                jsonObject.put("max_storage", 10);
                jsonObject.put("test_times", 10);  // 默认的拍照次数限制
                Files.write(configFile.toPath(), jsonObject.toString().getBytes());
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            Log.i(TAG, "Content: " + content);
            JSONObject jsonObject = new JSONObject(content);
            maxStorage = jsonObject.optInt("max_storage", 10);
            testTimes = jsonObject.optInt("test_times", 10);  // 读取拍照次数限制
            if (testTimes == 0) {
                isZero = true;
            }
            Log.d("isZero", "isZero: " + isZero);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error loading config", e);
            showErrorToast("Error loading config: " + e.getMessage());
        }
        updateSuccessfulPhotosText();
    }

    private void updateConfig(int additionalPhotos) throws IOException, JSONException {
        String content = new String(Files.readAllBytes(configFile.toPath()));
        Log.d(TAG, "ContentA: " + content);
        JSONObject jsonObject = new JSONObject(content);
        int updatedPhotoCount = jsonObject.optInt("successful_photos", 0) + additionalPhotos;
        jsonObject.put("successful_photos", updatedPhotoCount);
        try (FileWriter file = new FileWriter(configFile)) {
            file.write(jsonObject.toString());
        }
        updateSuccessfulPhotosText();
    }

    private void resetPhotoCount() throws IOException, JSONException {
        String content = new String(Files.readAllBytes(configFile.toPath()));
        Log.d(TAG, "ContentA: " + content);
        JSONObject jsonObject = new JSONObject(content);
        jsonObject.put("successful_photos", 0);
        try (FileWriter file = new FileWriter(configFile)) {
            file.write(jsonObject.toString());
        }
        // 更新TextView
        runOnUiThread(() -> tvSuccessfulPhotos.setText("The number of Successful Photos: 0"));
    }

    private void deleteAllPhotos() {
        new Thread(() -> {
            File[] files = photoDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted success");
                    } else {
                        Log.d(TAG, "Failed to delete: " + file.getAbsolutePath());
                    }
                }
            }
        }).start();
    }

    private void stopCamera() {
        isStop = true;
    }

    private void updateSuccessfulPhotosText() {
        runOnUiThread(() -> {
            try {
                String content = new String(Files.readAllBytes(configFile.toPath()));
                JSONObject jsonObject = new JSONObject(content);
                int successfulPhotos = jsonObject.optInt("successful_photos", 0);
                tvSuccessfulPhotos.setText("The number of Successful Photos: " + successfulPhotos);
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error updating successful photos text", e);
                showErrorToast("Error updating successful photos text: " + e.getMessage());
            }
        });
    }

    private void showErrorToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permissions granted
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
//        if (cameraHelper != null) {
//            try {
//                cameraHelper.onDestroyHelper();
//            } catch (Exception e) {
//                Log.e(TAG, "Error destroying camera helper", e);
//                showErrorToast("Error destroying camera helper: " + e.getMessage());
//            }
//        }
    }
}
