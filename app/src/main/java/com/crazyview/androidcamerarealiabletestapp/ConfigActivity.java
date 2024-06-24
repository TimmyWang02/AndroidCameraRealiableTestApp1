package com.crazyview.androidcamerarealiabletestapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ConfigActivity extends AppCompatActivity {

    private EditText editTextTestTime;
    private EditText editTextTestInterval;
    private EditText maxStorage;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch switchFlash;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch switchFrontCamera;  // 新增的 Switch 变量

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        // Set up the toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        editTextTestTime = findViewById(R.id.editTextTestTime);
        editTextTestInterval = findViewById(R.id.editTextTestInterval);
        maxStorage = findViewById(R.id.maxStorage);
        switchFlash = findViewById(R.id.switchFlash);
        switchFrontCamera = findViewById(R.id.switchFrontCamera);  // 初始化新的 Switch 控件

        loadJsonConfig();  // 加载配置

        // Add text changed listeners to EditTexts
        editTextTestTime.addTextChangedListener(createTextWatcher());
        editTextTestInterval.addTextChangedListener(createTextWatcher());
        maxStorage.addTextChangedListener(createTextWatcher());
        switchFlash.setOnCheckedChangeListener((buttonView, isChecked) -> saveJsonConfig());
        switchFrontCamera.setOnCheckedChangeListener((buttonView, isChecked) -> saveJsonConfig());  // 添加新的 Switch 监听器

        // Handle toolbar navigation click
        toolbar.setNavigationOnClickListener(v -> {
            saveJsonConfig();
            Intent intent = new Intent(ConfigActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        });
    }

    private TextWatcher createTextWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                saveJsonConfig();
            }
        };
    }

    public void saveJsonConfig() {
        String jsonConfig = generateJsonConfig();
        if (jsonConfig != null) {
            File configFile = new File(getExternalFilesDir(null), "config.json");
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(jsonConfig);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String generateJsonConfig() {
        try {
            JSONObject config = new JSONObject();
            config.put("test_times", Integer.parseInt(editTextTestTime.getText().toString()));
            config.put("test_interval", Integer.parseInt(editTextTestInterval.getText().toString()));
            config.put("max_storage", Integer.parseInt(maxStorage.getText().toString()));
            config.put("flash_enabled", switchFlash.isChecked());
            config.put("is_front_camera", switchFrontCamera.isChecked());  // 添加新的变量

            return config.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void loadJsonConfig() {
        File configFile = new File(getExternalFilesDir(null), "config.json");
        if (configFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(configFile.getPath())), StandardCharsets.UTF_8);
                JSONObject jsonObject = new JSONObject(content);
                editTextTestTime.setText(jsonObject.optString("test_times", ""));
                editTextTestInterval.setText(jsonObject.optString("test_interval", ""));
                maxStorage.setText(jsonObject.optString("max_storage", ""));
                switchFlash.setChecked(jsonObject.optBoolean("flash_enabled", false));
                switchFrontCamera.setChecked(jsonObject.optBoolean("is_front_camera", false));  // 加载新的变量
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }
    }
}
