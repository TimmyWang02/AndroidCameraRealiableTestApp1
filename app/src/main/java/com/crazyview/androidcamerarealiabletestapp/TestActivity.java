package com.crazyview.androidcamerarealiabletestapp;

import static java.lang.Thread.sleep;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
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
    private int successCounter = 0;
    private int failureCounter = 0;
    private File photoDirectory;
    private File configFile;
    private boolean isStop = false;
    private boolean isZero = false;
    private boolean switchFlash;
    private boolean isFrontCamera;  // 新增的变量
    private final ConditionVariable conditionVariable = new ConditionVariable();
    private AlertDialog exitDialog; // 声明对话框

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
            onBackPressed();
        });

        textView = findViewById(R.id.texture);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvSuccessfulPhotos = findViewById(R.id.tv_successful_photos);

        configFile = new File(getExternalFilesDir(null), "config.json");
        photoDirectory = new File(Environment.getExternalStorageDirectory() + "/Download");
//        photoDirectory = new File("/sdcard/camera/");
//        photoDirectory.mkdir();

        if (!hasPermissions()) {
            requestPermissions();
        }

        btnStart.setOnClickListener(v -> {
            try {
                resetPhotoCount();
                loadConfig();
                startCamera();
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error resetting photo count before start", e);
                showErrorToast("Error resetting photo count before start: " + e.getMessage());
            }
        });

        btnStop.setOnClickListener(v -> stopCamera());
    }

    @Override
    public void onBackPressed() {
        // 在显示对话框之前检查 Activity 是否仍然处于活动状态
        if (!isFinishing() && !isDestroyed()) {
            exitDialog = new AlertDialog.Builder(this)
                    .setMessage("Confirm Exit")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        isStop = true;  // 将 isStop 设置为 true
                        Intent intent = new Intent(TestActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();  // 结束当前的 TestActivity
                    })
                    .setNegativeButton("No", (dialog, which) -> {
                        // 用户选择不退出时，关闭对话框
                        dialog.dismiss();
                    })
                    .show();
        }
    }

    @Override
    protected void onDestroy() {
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss(); // 关闭对话框
        }
        if (cameraHelper != null) {
            cameraHelper.onDestroyHelper(); // 释放 cameraHelper 资源
        }
        super.onDestroy();
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

        // 根据 isFrontCamera 的值来设置 Camera2Helper 实例的参数
        String cameraId = isFrontCamera ? "1" : "0";
        cameraHelper = Camera2Helper.getInstance(this, textView, null, cameraId);

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
        testCounter = 0;
        successCounter = 0;
        failureCounter = 0;
        new Thread(() -> {
            conditionVariable.block();
            conditionVariable.close();

            while (!isStop) {
                try {
                    String fileName = "photo" + (testCounter + 1) + ".jpg";
                    File file = new File(photoDirectory, fileName);
                    Log.d("PATH", file.toString());

                    cameraHelper.takePicture(file, switchFlash);
                    conditionVariable.block();
                    conditionVariable.close();
                    sleep(1000);

                    if (file.exists()) {
                        updateConfig(1);
                        successCounter++;
                        Log.i("aaaaa", "Photo taken: " + testCounter);
                    } else {
                        Log.e(TAG, "Photo file not found: " + fileName);
                        showErrorToast("Photo error: file not found " + fileName);
                        failureCounter++;
                    }

                    testCounter++;

                    if (testCounter >= testTimes && !isZero) {
                        isStop = true;
                    }

                    if (testCounter % maxStorage == 0) {
                        deleteAllPhotos();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during camera operation", e);
                    showErrorToast("Error during camera operation: " + e.getMessage());
                    failureCounter++;
                    testCounter++;
                }
            }

            runOnUiThread(() -> {
                try {
                    saveResults();
                    cameraHelper.onDestroyHelper();
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                } catch (Exception e) {
                    Log.e(TAG, "Error destroying camera helper", e);
                    showErrorToast("Error destroying camera helper: " + e.getMessage());
                }
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
                jsonObject.put("test_times", 10);
                jsonObject.put("switchFlash", false);
                jsonObject.put("is_front_camera", false);  // 添加默认值
                Files.write(configFile.toPath(), jsonObject.toString().getBytes());
            }
            String content = new String(Files.readAllBytes(configFile.toPath()));
            Log.i(TAG, "Content: " + content);
            JSONObject jsonObject = new JSONObject(content);
            maxStorage = jsonObject.optInt("max_storage", 10);
            testTimes = jsonObject.optInt("test_times", 10);
            switchFlash = jsonObject.optBoolean("switchFlash", false);
            isFrontCamera = jsonObject.optBoolean("is_front_camera", false);  // 读取 isfront 值
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

    private void saveResults() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("total_tests", testCounter);
            jsonObject.put("successful_photos", successCounter);
            jsonObject.put("failed_photos", failureCounter);
            File resultFile = new File(getExternalFilesDir(null), "result.json");
            try (FileWriter fileWriter = new FileWriter(resultFile)) {
                fileWriter.write(jsonObject.toString());
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error saving results", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }
}
