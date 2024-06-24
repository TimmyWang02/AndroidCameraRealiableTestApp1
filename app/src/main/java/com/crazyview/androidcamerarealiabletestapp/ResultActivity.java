package com.crazyview.androidcamerarealiabletestapp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

public class ResultActivity extends AppCompatActivity {

    private TextView tvTotalTests, tvSuccessfulPhotos, tvFailedPhotos;
    private Button btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        // 初始化 TextView 元素
        tvTotalTests = findViewById(R.id.tv_total_tests);
        tvSuccessfulPhotos = findViewById(R.id.tv_successful_photos);
        tvFailedPhotos = findViewById(R.id.tv_failed_photos);

        // 初始化返回按钮
        btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> onBackPressed());

        // 加载结果并显示
        loadResults();
    }

    private void loadResults() {
        File resultFile = new File(getExternalFilesDir(null), "result.json");
        if (resultFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(resultFile.toPath()));
                JSONObject jsonObject = new JSONObject(content);
                int totalTests = jsonObject.optInt("total_tests", 0);
                int successfulPhotos = jsonObject.optInt("successful_photos", 0);
                int failedPhotos = jsonObject.optInt("failed_photos", 0);

                tvTotalTests.setText("Total Tests: " + totalTests);
                tvSuccessfulPhotos.setText("Successful Photos: " + successfulPhotos);
                tvFailedPhotos.setText("Failed Photos: " + failedPhotos);
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }
    }
}
