package com.fmekran.player;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText serverUrlInput;
    private EditText screenIdInput;
    private EditText macInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        serverUrlInput = findViewById(R.id.serverUrlInput);
        screenIdInput  = findViewById(R.id.screenIdInput);
        macInput       = findViewById(R.id.macInput);
        Button saveBtn = findViewById(R.id.saveButton);

        SharedPreferences prefs = getSharedPreferences("fmekran_prefs", MODE_PRIVATE);
        serverUrlInput.setText(prefs.getString("server_url", "http://34.78.135.59"));
        screenIdInput.setText(prefs.getString("screen_id", ""));
        macInput.setText(prefs.getString("mac_address", ""));

        saveBtn.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("server_url",  serverUrlInput.getText().toString().trim());
            editor.putString("screen_id",   screenIdInput.getText().toString().trim());
            editor.putString("mac_address", macInput.getText().toString().trim());
            editor.apply();

            Toast.makeText(this, "Kaydedildi, yeniden başlatılıyor...", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, PlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }
}
