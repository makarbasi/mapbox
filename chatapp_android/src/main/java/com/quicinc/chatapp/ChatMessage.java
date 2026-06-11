// ---------------------------------------------------------------------
// Copyright (c) 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.chatapp;

/**
 * ChatMessage: Holds information about each message within Chat
 */
public class ChatMessage {

    public String mMessage;
    public int mLength;
    public MessageSender mSender;
    public String mMapImageUrl;

    public ChatMessage(String msg, MessageSender sender) {
        mMessage = msg;
        mLength = msg.length();
        mSender = sender;
        mMapImageUrl = null;
    }

    public ChatMessage(String msg, MessageSender sender, String mapImageUrl) {
        mMessage = msg;
        mLength = msg.length();
        mSender = sender;
        mMapImageUrl = mapImageUrl;
    }

    public boolean isMessageFromUser() {
        return mSender == MessageSender.USER;
    }

    public String getMessage() {
        return mMessage;
    }

    public String getMapImageUrl() {
        return mMapImageUrl;
    }
}
