// ---------------------------------------------------------------------
// Copyright (c) 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.chatapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Message_RecyclerViewAdapter extends RecyclerView.Adapter<Message_RecyclerViewAdapter.MyViewHolder> {

    Context context;
    ArrayList<ChatMessage> messages = new ArrayList<ChatMessage>(1000);
    private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public Message_RecyclerViewAdapter(Context context, ArrayList<ChatMessage> messages) {
        this.context = context;
        this.messages = messages;
    }

    @NonNull
    @Override
    public Message_RecyclerViewAdapter.MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.chat_row, parent, false);

        return new Message_RecyclerViewAdapter.MyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Message_RecyclerViewAdapter.MyViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);

        // Hide all layouts by default
        holder.mLeftChatLayout.setVisibility(View.GONE);
        holder.mRightChatLayout.setVisibility(View.GONE);
        holder.mToolChatLayout.setVisibility(View.GONE);
        holder.mMapImage.setVisibility(View.GONE);

        switch (msg.mSender) {
            case USER:
                holder.mUserMessage.setText(msg.getMessage());
                holder.mRightChatLayout.setVisibility(View.VISIBLE);
                break;

            case TOOL:
                holder.mToolMessage.setText(msg.getMessage());
                holder.mToolChatLayout.setVisibility(View.VISIBLE);
                break;

            case MAP:
                holder.mLeftChatLayout.setVisibility(View.VISIBLE);
                holder.mBotMessage.setVisibility(View.GONE);
                if (msg.getMapImageUrl() != null) {
                    holder.mMapImage.setVisibility(View.VISIBLE);
                    loadMapImage(msg.getMapImageUrl(), holder.mMapImage);
                }
                break;

            case BOT:
            default:
                holder.mBotMessage.setVisibility(View.VISIBLE);
                holder.mBotMessage.setText(msg.getMessage());
                holder.mLeftChatLayout.setVisibility(View.VISIBLE);

                // Check if this bot message also has a map
                if (msg.getMapImageUrl() != null) {
                    holder.mMapImage.setVisibility(View.VISIBLE);
                    loadMapImage(msg.getMapImageUrl(), holder.mMapImage);
                }
                break;
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
    }

    /**
     * updateBotMessage: updates / inserts message on behalf of Bot
     *
     * @param bot_message message to update or insert
     * @return newly added message
     */
    public String updateBotMessage(String bot_message) {
        boolean lastMessageFromBot = false;
        ChatMessage lastMessage;

        if (messages.size() > 1) {
            lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.mSender == MessageSender.BOT) {
                lastMessageFromBot = true;
            }
        } else {
            addMessage(new ChatMessage(bot_message, MessageSender.BOT));
        }

        if (lastMessageFromBot) {
            messages.get(messages.size() - 1).mMessage = messages.get(messages.size() - 1).mMessage + bot_message;
        } else {
            addMessage(new ChatMessage(bot_message, MessageSender.BOT));
        }
        return messages.get(messages.size() - 1).mMessage;
    }

    /**
     * Loads a map image from URL asynchronously and sets it on the ImageView.
     */
    private void loadMapImage(String imageUrl, ImageView imageView) {
        imageView.setTag(imageUrl);
        imageLoader.execute(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                connection.disconnect();

                if (bitmap != null) {
                    mainHandler.post(() -> {
                        // Check tag to avoid recycled view issues
                        if (imageUrl.equals(imageView.getTag())) {
                            imageView.setImageBitmap(bitmap);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("ChatApp", "Failed to load map image: " + e.getMessage());
            }
        });
    }

    public static class MyViewHolder extends RecyclerView.ViewHolder {

        TextView mUserMessage;
        TextView mBotMessage;
        TextView mToolMessage;
        LinearLayout mLeftChatLayout;
        LinearLayout mRightChatLayout;
        LinearLayout mToolChatLayout;
        ImageView mMapImage;

        public MyViewHolder(@NonNull View itemView) {
            super(itemView);

            mBotMessage = itemView.findViewById(R.id.bot_message);
            mUserMessage = itemView.findViewById(R.id.user_message);
            mToolMessage = itemView.findViewById(R.id.tool_message);
            mLeftChatLayout = itemView.findViewById(R.id.left_chat_layout);
            mRightChatLayout = itemView.findViewById(R.id.right_chat_layout);
            mToolChatLayout = itemView.findViewById(R.id.tool_chat_layout);
            mMapImage = itemView.findViewById(R.id.map_image);
        }
    }
}
