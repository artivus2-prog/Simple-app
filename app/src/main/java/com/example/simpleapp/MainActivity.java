package com.example.simpleapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private TextView statusText;
    private Button botButton;
    private boolean isBotRunning = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        statusText = findViewById(R.id.statusText);
        botButton = findViewById(R.id.botButton);
        
        botButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isBotRunning) {
                    stopBot();
                } else {
                    startBot();
                }
            }
        });
    }
    
    private void startBot() {
        isBotRunning = true;
        statusText.setText("🤖 БОТ ЗАПУЩЕН");
        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        botButton.setText("ОСТАНОВИТЬ БОТА");
        botButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
    }
    
    private void stopBot() {
        isBotRunning = false;
        statusText.setText("⚪ БОТ ОСТАНОВЛЕН");
        statusText.setTextColor(getResources().getColor(android.R.color.darker_gray));
        botButton.setText("ЗАПУСТИТЬ БОТА");
        botButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light));
    }
}
