package com.crazyview.androidcamerarealiabletestapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private Button btnConfig;
    private Button btnTest;
    private Button btnReset;
    private Button btnResult;
    private Button btnDebug;
    private File configFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化配置文件路径
        configFile = new File(getExternalFilesDir(null), "config.json");

        // 初始化 Config 按钮
        btnConfig = findViewById(R.id.btn_config);
        btnConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 启动 ConfigActivity
                Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
                startActivity(intent);
            }
        });

        // 初始化 Test 按钮并设置点击事件
        btnTest = findViewById(R.id.btn_test);
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 启动 TestActivity
                Intent intent = new Intent(MainActivity.this, TestActivity.class);
                startActivity(intent);
            }
        });

        // 初始化 Reset 按钮并设置点击事件
        btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> resetFunction());

        // 初始化 Result 按钮并设置点击事件
        btnResult = findViewById(R.id.btn_result);
        btnResult.setOnClickListener(v -> {
            // 启动 ResultActivity
            Intent intent = new Intent(MainActivity.this, ResultActivity.class);
            startActivity(intent);
        });

        // 初始化 Debug 按钮并设置点击事件
        btnDebug = findViewById(R.id.btn_debug);
        btnDebug.setOnClickListener(v -> {
            // 启动 DebugActivity
            Intent intent = new Intent(MainActivity.this, DebugActivity.class);
            startActivity(intent);
        });
    }

    private void resetFunction() {
        try {
            resetPhotoCount();
            Toast.makeText(this, "Reset successful_photos to 0", Toast.LENGTH_SHORT).show();
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error resetting photo count", e);
            Toast.makeText(this, "Error resetting photo count", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetPhotoCount() throws IOException, JSONException {
        if (configFile.exists()) {
            String content = new String(Files.readAllBytes(configFile.toPath()));
            JSONObject jsonObject = new JSONObject(content);
            jsonObject.put("successful_photos", 0);
            try (FileWriter fileWriter = new FileWriter(configFile)) {
                fileWriter.write(jsonObject.toString());
            }
        } else {
            // 配置文件不存在时创建新的配置文件
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("successful_photos", 0);
            jsonObject.put("max_storage", 50);
            jsonObject.put("test_times", 50);
            try (FileWriter fileWriter = new FileWriter(configFile)) {
                fileWriter.write(jsonObject.toString());
            }
        }
    }
}
