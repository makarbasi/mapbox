package com.quicinc.chatapp;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.quicinc.chatapp.location.HomeAddressManager;
import com.quicinc.chatapp.mapbox.McpMapboxClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * McpSettingsActivity: Settings screen for Mapbox MCP configuration.
 * Allows user to set their Mapbox Access Token and Home Address.
 * Tests the actual MCP connection to https://mcp.mapbox.com/mcp.
 */
public class McpSettingsActivity extends AppCompatActivity {

    private static final String TAG = "McpSettings";
    private EditText tokenInput;
    private EditText homeAddressInput;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tokenInput = findViewById(R.id.mapbox_token_input);
        homeAddressInput = findViewById(R.id.home_address_input);
        statusText = findViewById(R.id.settings_status);
        Button saveButton = findViewById(R.id.save_settings_button);
        Button testButton = findViewById(R.id.test_connection_button);

        // Load existing settings
        String existingToken = HomeAddressManager.getMapboxToken(this);
        if (existingToken != null) {
            tokenInput.setText(existingToken);
        }
        String existingAddress = HomeAddressManager.getHomeAddress(this);
        if (existingAddress != null) {
            homeAddressInput.setText(existingAddress);
        }

        saveButton.setOnClickListener(v -> saveSettings());
        testButton.setOnClickListener(v -> testConnection());
    }

    private void saveSettings() {
        String token = tokenInput.getText().toString().trim();
        String address = homeAddressInput.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "Please enter a Mapbox Access Token", Toast.LENGTH_SHORT).show();
            return;
        }

        HomeAddressManager.setMapboxToken(this, token);

        if (!address.isEmpty()) {
            HomeAddressManager.setHomeAddress(this, address);
        }

        showStatus("✅ Settings saved.", "#4CAF50");
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
    }

    private void testConnection() {
        String token = tokenInput.getText().toString().trim();

        if (token.isEmpty()) {
            showStatus("❌ Please enter a Mapbox token first.", "#F44336");
            return;
        }

        showStatus("⏳ Connecting to Mapbox MCP server...", "#2196F3");

        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(() -> {
            try {
                McpMapboxClient mcpClient = new McpMapboxClient(token);
                boolean connected = mcpClient.connect();

                runOnUiThread(() -> {
                    if (connected) {
                        int toolCount = mcpClient.getTools().size();
                        StringBuilder toolList = new StringBuilder();
                        toolList.append("✅ MCP Connected!\n");
                        toolList.append("Server: mcp.mapbox.com\n");
                        toolList.append("Tools discovered: ").append(toolCount).append("\n");

                        mcpClient.getTools().forEach(tool -> {
                            toolList.append("  • ").append(tool.getName()).append("\n");
                        });

                        showStatus(toolList.toString(), "#4CAF50");
                    } else {
                        showStatus("❌ Failed to connect to MCP server.\nCheck your token.", "#F44336");
                    }
                });

                // Clean up test connection
                mcpClient.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "MCP test connection failed", e);
                runOnUiThread(() -> showStatus("❌ Error: " + e.getMessage(), "#F44336"));
            }
        });
    }

    private void showStatus(String message, String color) {
        statusText.setText(message);
        statusText.setTextColor(android.graphics.Color.parseColor(color));
        statusText.setVisibility(View.VISIBLE);
    }
}
