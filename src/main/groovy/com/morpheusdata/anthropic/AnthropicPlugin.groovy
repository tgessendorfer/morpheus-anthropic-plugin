/*
 * Copyright 2026 Thomas Gessendorfer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Derived from the Apache 2.0 licensed HPE morpheus-copilot-plugin.
 */
package com.morpheusdata.anthropic

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the Anthropic Claude LLM Engine integration.
 * Registers the AnthropicProvider which implements LlmProvider against the
 * native Anthropic Messages API (/v1/messages).
 */
@Slf4j
class AnthropicPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-anthropic-plugin'
	}

	@Override
	void initialize() {
		this.setName('Anthropic Claude')
		AnthropicProvider anthropicProvider = new AnthropicProvider(this, morpheus)
		this.pluginProviders.put(anthropicProvider.code, anthropicProvider)
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
