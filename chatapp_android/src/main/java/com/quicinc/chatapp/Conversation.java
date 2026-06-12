// ---------------------------------------------------------------------
// Copyright (c) 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.chatapp;

import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.quicinc.chatapp.location.HomeAddressManager;
import com.quicinc.chatapp.location.LocationHelper;
import com.quicinc.chatapp.mapbox.MapboxToolRegistry;
import com.quicinc.chatapp.mcp.ToolArgSanitizer;
import com.quicinc.chatapp.mcp.ToolCall;
import com.quicinc.chatapp.mcp.ToolCallParser;
import com.quicinc.chatapp.mcp.ToolResultFormatter;

import org.json.JSONObject;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class Conversation extends AppCompatActivity {

    private static final String TAG = "ChatApp";
    private static final int MAX_TOOL_ROUNDS = 2;

    ArrayList<ChatMessage> messages = new ArrayList<ChatMessage>(1000);

    private static final String cWelcomeMessage = "Hi! How can I help you?";
    public static final String cConversationActivityKeyHtpConfig = "htp_config_path";
    public static final String cConversationActivityKeyModelName = "model_dir_name";

    // Location & Mapbox
    private LocationHelper locationHelper;
    private MapboxToolRegistry toolRegistry;
    // Hardcoded to San Diego, CA (downtown / Gaslamp Quarter)
    private double currentLat = 32.7157;
    private double currentLon = -117.1611;
    private boolean locationAvailable = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.chat);
        RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
        Message_RecyclerViewAdapter adapter = new Message_RecyclerViewAdapter(this, messages);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Disable change animations
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator){
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        ImageButton sendUserMsgButton = (ImageButton) findViewById(R.id.send_button);
        TextView userMsg = (TextView) findViewById(R.id.user_input);
        TextView statsBar = (TextView) findViewById(R.id.stats_bar);

        try {
            // Make QNN libraries discoverable
            String nativeLibPath = getApplicationContext().getApplicationInfo().nativeLibraryDir;
            Os.setenv("ADSP_LIBRARY_PATH", nativeLibPath, true);
            Os.setenv("LD_LIBRARY_PATH", nativeLibPath, true);

            // Get information from MainActivity regarding
            //  - Model to run
            //  - HTP config to use
            Bundle bundle = getIntent().getExtras();
            if (bundle == null) {
                Log.e(TAG, "Error getting additional info from bundle.");
                Toast.makeText(this, "Unexpected error observed. Exiting app.", Toast.LENGTH_LONG).show();
                finish();
            }

            String htpExtensionsDir = bundle.getString(cConversationActivityKeyHtpConfig);
            String modelName = bundle.getString(cConversationActivityKeyModelName);
            String externalCacheDir = this.getExternalCacheDir().getAbsolutePath().toString();
            externalCacheDir="/data/local/tmp/chatapp";
            String modelDir = Paths.get(externalCacheDir, "models", modelName).toString();

            // Load Model
            GenieWrapper genieWrapper = new GenieWrapper(modelDir, htpExtensionsDir);
            Log.i(TAG, modelName + " Loaded.");

            // Initialize Mapbox tools if configured
            initializeMapboxTools(genieWrapper);

            // Initialize location
            initializeLocation();

            messages.add(new ChatMessage(cWelcomeMessage, MessageSender.BOT));

            // Get response from Bot once user message is sent
            sendUserMsgButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {

                    if (userMsg.getTextSize() != 0) {
                        String userInputMsg = userMsg.getText().toString();
                        // Reset user message box
                        userMsg.setText("");

                        // Insert user message in the conversation
                        int start = adapter.getItemCount();
                        adapter.addMessage(new ChatMessage(userInputMsg, MessageSender.USER));
                        adapter.addMessage(new ChatMessage("", MessageSender.BOT));
                        adapter.notifyItemRangeInserted(start, 2);

                        int botResponseMsgIndex = adapter.getItemCount() - 1;
                        recyclerView.smoothScrollToPosition(botResponseMsgIndex);

                        long inferenceStartTime = SystemClock.elapsedRealtimeNanos();
                        AtomicLong firstTokenTime = new AtomicLong(-1);
                        AtomicLong lastTokenTime  = new AtomicLong(-1);
                        AtomicInteger tokenCount  = new AtomicInteger(0);

                        ExecutorService service = Executors.newSingleThreadExecutor();
                        service.execute(new Runnable() {
                            @Override
                            public void run() {
                                // Build context-enhanced prompt
                                String enhancedPrompt = buildContextPrompt(userInputMsg);

                                // Streaming callback for tokens
                                StringCallback streamingCallback = new StringCallback() {
                                    @Override
                                    public void onNewString(String response) {
                                        long now = SystemClock.elapsedRealtimeNanos();
                                        tokenCount.incrementAndGet();
                                        lastTokenTime.set(now);
                                        firstTokenTime.compareAndSet(-1, now);

                                        runOnUiThread(() -> {
                                            adapter.updateBotMessage(response);
                                            adapter.notifyItemChanged(adapter.getItemCount() - 1);

                                            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                                            if (lm instanceof LinearLayoutManager) {
                                                LinearLayoutManager layoutManager = (LinearLayoutManager) lm;
                                                int lastVisible = layoutManager.findLastVisibleItemPosition();

                                                if (lastVisible >= messages.size() - 2) {
                                                    recyclerView.scrollToPosition(messages.size() - 1);
                                                }
                                            }

                                            long ttftMs = (firstTokenTime.get() - inferenceStartTime) / 1_000_000;
                                            double tps = 0;
                                            if (tokenCount.get() > 1 && lastTokenTime.get() > firstTokenTime.get()) {
                                                tps = (tokenCount.get() - 1.0) * 1e9 / (lastTokenTime.get() - firstTokenTime.get());
                                            }
                                            statsBar.setText(String.format("TTFT: %d ms  |  TPS: %.1f tok/s", ttftMs, tps));
                                            statsBar.setVisibility(View.VISIBLE);
                                        });
                                    }
                                };

                                // Get initial LLM response
                                StringBuilder fullResponse = new StringBuilder();
                                genieWrapper.getResponseForPrompt(enhancedPrompt, new StringCallback() {
                                    @Override
                                    public void onNewString(String response) {

                                        fullResponse.append(response);
                                        streamingCallback.onNewString(response);
                                    }
                                });

                                // DEBUG: Log the full LLM response to see what it generated
                                String llmOutput = fullResponse.toString();
                                Log.e("toolcalling", "=== LLM FULL RESPONSE (" + llmOutput.length() + " chars) ===");
                                Log.e("toolcalling", llmOutput);
                                Log.e("toolcalling", "=== toolRegistry null? " + (toolRegistry == null) + " ===");
                                Log.e("toolcalling", "=== hasToolCall? " + ToolCallParser.hasToolCall(llmOutput) + " ===");
                                Log.e("toolcalling", "=== contains <tool_call>? " + llmOutput.contains("<tool_call>") + " ===");
                                Log.e("toolcalling", "=== contains </tool_call>? " + llmOutput.contains("</tool_call>") + " ===");

                                // Tool-calling loop
                                if (toolRegistry != null) {
                                    int round = 0;
                                    while (ToolCallParser.hasToolCall(fullResponse.toString()) && round < MAX_TOOL_ROUNDS) {
                                        ToolCall toolCall = ToolCallParser.parse(fullResponse.toString());
                                        if (toolCall == null) break;

                                        Log.e("toolcalling", "Tool call detected: " + toolCall.getToolName());

                                        // Show tool status in chat
                                        runOnUiThread(() -> {
                                            int toolMsgPos = adapter.getItemCount();
                                            adapter.addMessage(new ChatMessage(
                                                "🔧 Calling " + toolCall.getToolName() + "...",
                                                MessageSender.TOOL));
                                            adapter.notifyItemInserted(toolMsgPos);
                                            recyclerView.scrollToPosition(toolMsgPos);
                                        });

                                        // Build correct args from context — LLM only picks the tool
                                        String homeAddr = HomeAddressManager.getHomeAddress(Conversation.this);
                                        JSONObject builtArgs = ToolArgSanitizer.buildArgs(
                                            toolCall.getToolName(), toolCall.getArguments(),
                                            enhancedPrompt, currentLat, currentLon,
                                            homeAddr != null ? homeAddr : "");

                                        // Execute the tool with built arguments
                                        String toolResult = toolRegistry.execute(
                                            toolCall.getToolName(), builtArgs);
                                        Log.e("toolcalling", "Tool result (" + toolResult.length() + " chars): "
                                            + toolResult.substring(0, Math.min(300, toolResult.length())));

                                        // Check for map image in result
                                        try {
                                            JSONObject resultJson = new JSONObject(toolResult);
                                            if (resultJson.has("mapImageUrl")) {
                                                String mapUrl = resultJson.getString("mapImageUrl");
                                                runOnUiThread(() -> {
                                                    int mapPos = adapter.getItemCount();
                                                    adapter.addMessage(new ChatMessage(
                                                        "", MessageSender.MAP, mapUrl));
                                                    adapter.notifyItemInserted(mapPos);
                                                    recyclerView.scrollToPosition(mapPos);
                                                });
                                            }
                                        } catch (Exception e) {
                                            // Not JSON or no map URL — that's fine
                                        }

                                        // Pre-process MCP result into simple text.
                                        // The 3B model can't reliably parse raw JSON,
                                        // so we extract key info in Java first.
                                        String formattedResult = ToolResultFormatter.format(
                                            toolCall.getToolName(), toolResult);
                                        Log.i(TAG, "Formatted for LLM: " + formattedResult);

                                        // Add new bot message placeholder for continued response
                                        runOnUiThread(() -> {
                                            int newBotPos = adapter.getItemCount();
                                            adapter.addMessage(new ChatMessage("", MessageSender.BOT));
                                            adapter.notifyItemInserted(newBotPos);
                                        });

                                        // Feed formatted result back to LLM with instruction
                                        fullResponse.setLength(0);
                                        final String resultForLlm = "Result: " + formattedResult
                                            + "\nAnswer the user's question based on this.";
                                        genieWrapper.submitToolResponse(resultForLlm, new StringCallback() {
                                            @Override
                                            public void onNewString(String response) {
                                                fullResponse.append(response);
                                                streamingCallback.onNewString(response);
                                            }
                                        });

                                        round++;
                                    }
                                }
                            }
                        });

                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error during conversation with Chatbot: " + e.toString());
            Toast.makeText(this, "Unexpected error observed. Exiting app.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * Initialize Mapbox MCP connection if a token is configured.
     * Connects to https://mcp.mapbox.com/mcp via MCP protocol,
     * discovers tools, and injects them into the LLM system prompt.
     */
    private void initializeMapboxTools(GenieWrapper genieWrapper) {
        String token = HomeAddressManager.getMapboxToken(this);
        if (token == null || token.isEmpty()) {
            Log.i(TAG, "Mapbox not configured — running without location tools.");
            return;
        }

        toolRegistry = new MapboxToolRegistry(token);

        // MCP connection requires network I/O — run on background thread
        ExecutorService mcpService = Executors.newSingleThreadExecutor();
        mcpService.execute(() -> {
            boolean success = toolRegistry.initialize();
            if (success) {
                String toolsPrompt = toolRegistry.getToolsPrompt();
                genieWrapper.setToolDefinitions(toolsPrompt);
                Log.i(TAG, "Mapbox MCP connected. " + toolsPrompt.length() + " chars of tool definitions.");
                runOnUiThread(() -> Toast.makeText(
                    Conversation.this, "Mapbox MCP connected ✓", Toast.LENGTH_SHORT).show());
            } else {
                Log.e(TAG, "Failed to connect to Mapbox MCP server.");
                toolRegistry = null;
                runOnUiThread(() -> Toast.makeText(
                    Conversation.this, "Mapbox MCP connection failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Initialize GPS location services.
     */
    private void initializeLocation() {
        if (!LocationHelper.hasLocationPermission(this)) {
            LocationHelper.requestLocationPermission(this);
            return;
        }

        locationHelper = new LocationHelper(this);
        locationHelper.getCurrentLocation(this, new LocationHelper.LocationCallback() {
            @Override
            public void onLocationReceived(Location location) {
                currentLat = location.getLatitude();
                currentLon = location.getLongitude();
                locationAvailable = true;
                Log.i(TAG, String.format("Location acquired: %.4f, %.4f", currentLat, currentLon));
            }

            @Override
            public void onLocationError(String error) {
                Log.w(TAG, "Location error: " + error);
                locationAvailable = false;
            }
        });
    }

    /**
     * Build an enhanced prompt that includes location context.
     */
    private String buildContextPrompt(String userInput) {
        StringBuilder prompt = new StringBuilder();

        if (locationAvailable) {
            prompt.append(String.format("[Current location: %.4f, %.4f] ", currentLon, currentLat));
        }

        String homeAddress = HomeAddressManager.getHomeAddress(this);
        if (homeAddress != null && !homeAddress.isEmpty()) {
            prompt.append("[Home: ").append(homeAddress).append("] ");
        }

        prompt.append(userInput);
        return prompt.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LocationHelper.LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                initializeLocation();
            } else {
                Log.w(TAG, "Location permission denied.");
                Toast.makeText(this, "Location access denied. Location-based features will be limited.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
