package com.crazyview.androidcamerarealiabletestapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;

public class DebugActivity extends AppCompatActivity {

    private static final String TAG = "DebugActivity";
    private Camera2Helper cameraHelper;
    private AutoFitTextureView textView;
    private File photoDirectory;
    private boolean isTestComplete = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        textView = findViewById(R.id.texture);

        photoDirectory = new File(Environment.getExternalStorageDirectory() + "/Download/debug_test");

        if (!hasPermissions()) {
            requestPermissions();
        } else {
            setupCameraAndStartTest();
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    private void setupCameraAndStartTest() {
        setupCamera();
        if (cameraHelper != null) {
            startTest();
        } else {
            Log.e(TAG, "CameraHelper initialization failed");
        }
    }

    private void setupCamera() {
        cameraHelper = Camera2Helper.getInstance(this, textView, null, "0");
        if (cameraHelper != null) {
            cameraHelper.setAfterDoListener(new Camera2Helper.AfterDoListener() {
                @Override
                public void onAfterPreviewBack() {
                    Log.d(TAG, "onAfterPreviewBack");
                }

                @Override
                public void onAfterTakePicture() {
                    Log.d(TAG, "onAfterTakePicture");
                    isTestComplete = true;
                    checkTestResult();
                }
            });
        }
    }

    private void startTest() {
        if (!photoDirectory.exists()) {
            photoDirectory.mkdirs();
        }

        File testPhoto = new File(photoDirectory, "test_photo.jpg");
        if (testPhoto.exists()) {
            testPhoto.delete();
        }

        cameraHelper.startCameraPreView();
        new Thread(() -> cameraHelper.takePicture(testPhoto, false)).start();
    }

    private void checkTestResult() {
        File testPhoto = new File(photoDirectory, "test_photo.jpg");
        if (testPhoto.exists()) {
            showToast("Test Passed: Photo taken successfully.");
        } else {
            showToast("Test Failed: Photo not taken.");
        }

        cameraHelper.onDestroyHelper();
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupCameraAndStartTest();
        } else {
            showToast("Permission Denied");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraHelper != null) {
            cameraHelper.onDestroyHelper();
        }
    }
}
