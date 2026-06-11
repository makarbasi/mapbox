// ---------------------------------------------------------------------
// Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
#pragma once

#include <string>

namespace AppUtils
{

class PromptHandler
{
  private:
    bool m_is_first_prompt = true;
    std::string m_system_prefix;
    std::string m_system_suffix;
    std::string m_user_prefix;
    std::string m_user_suffix;
    std::string m_assistant_prefix;
    std::string m_default_system_prompt;
    std::string m_tool_definitions;
    std::string m_ipython_prefix;
    std::string m_ipython_suffix;

  public:
    PromptHandler() = default;
    explicit PromptHandler(const std::string& models_path);
    std::string GetPromptWithTag(const std::string& user_prompt);
    void SetToolDefinitions(const std::string& tools);
    std::string GetToolResponsePrompt(const std::string& tool_result);
};

} // namespace AppUtils
