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

import com.morpheusdata.anthropic.sync.LlmModelsSync
import com.morpheusdata.anthropic.sync.LlmUsageSync
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.LlmProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.*
import com.morpheusdata.response.LlmStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * LlmProvider implementation for Anthropic Claude using the native Messages API.
 *
 * Morpheus speaks the OpenAI tool-calling convention internally (tool_calls /
 * tool_call_id in LlmChatMessage metadata). Anthropic instead uses tool_use and
 * tool_result content blocks. This provider translates in both directions so the
 * Morpheus MCP tool loop works unchanged against Claude.
 */
@Slf4j
class AnthropicProvider implements LlmProvider {

	Plugin plugin
	MorpheusContext morpheusContext
	AnthropicApiService apiService

	static final String PROVIDER_CODE = 'anthropic-claude'
	static final String PROVIDER_NAME = 'Anthropic Claude'
	static final String DEFAULT_API_URL = 'https://api.anthropic.com'
	static final String DEFAULT_CHAT_MODEL = 'claude-sonnet-4-6'
	static final Integer DEFAULT_MAX_OUTPUT_TOKENS = 8192
	static final Integer DEFAULT_THINKING_BUDGET_TOKENS = 4096
	static final Integer MIN_THINKING_BUDGET_TOKENS = 1024
	static final Long STANDARD_CONTEXT_WINDOW = 200000L
	static final Long LONG_CONTEXT_WINDOW = 1000000L

	AnthropicProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
		this.apiService = new AnthropicApiService()
	}

	@Override
	String getCode() { return PROVIDER_CODE }

	@Override
	String getName() { return PROVIDER_NAME }

	@Override
	MorpheusContext getMorpheus() { return this.morpheusContext }

	@Override
	Plugin getPlugin() { return this.plugin }

	@Override
	Icon getIcon() {
		// The filename carries the icon revision on purpose: plugin assets are served
		// from a stable URL, so reusing a name leaves browsers showing the previous
		// icon after an upgrade, and a hard reload does not always clear it.
		return new Icon(path: 'anthropic-mark.svg', darkPath: 'anthropic-mark-white.svg')
	}

	@Override
	String getDescription() {
		return 'Anthropic Claude models via the native Messages API, including tool use for MCP-backed Agents.'
	}

	@Override
	Boolean getCreatable() { return true }

	@Override
	Boolean getEnabled() { return true }

	@Override
	Boolean getChatSupported() { return true }

	@Override
	Boolean getStreamingChatSupported() { return true }

	@Override
	Boolean getEmbeddingSupported() { return false }

	@Override
	List<OptionType> getOptionTypes() {
		List<OptionType> optionTypes = []

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.serviceUrl",
			name: "Service URL",
			fieldName: "serviceUrl",
			fieldLabel: "API Endpoint",
			fieldContext: "domain",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 0,
			required: true,
			defaultValue: DEFAULT_API_URL
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.credential",
			name: "Credentials",
			inputType: OptionType.InputType.CREDENTIAL,
			fieldName: "type",
			fieldLabel: "Credentials",
			fieldContext: "credential",
			required: true,
			displayOrder: 1,
			defaultValue: "local",
			optionSource: "credentials",
			config: '{"credentialTypes":["api-key"]}'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.servicePassword",
			name: "API Key",
			inputType: OptionType.InputType.PASSWORD,
			fieldName: "servicePassword",
			fieldLabel: "API Key",
			fieldContext: "domain",
			displayOrder: 2,
			required: true,
			localCredential: true
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.apiVersion",
			name: "API Version",
			fieldName: "apiVersion",
			fieldLabel: "Anthropic API Version",
			fieldContext: "config",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 3,
			required: false,
			defaultValue: AnthropicApiService.DEFAULT_API_VERSION,
			helpText: 'Value sent in the anthropic-version header. Leave at the default unless Anthropic tells you otherwise.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.maxOutputTokens",
			name: "Max Output Tokens",
			fieldName: "maxOutputTokens",
			fieldLabel: "Default Max Output Tokens",
			fieldContext: "config",
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 4,
			required: false,
			defaultValue: DEFAULT_MAX_OUTPUT_TOKENS.toString(),
			helpText: 'The Messages API requires max_tokens on every request. Used when the caller does not supply one.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.promptCaching",
			name: "Prompt Caching",
			fieldName: "promptCaching",
			fieldLabel: "Enable Prompt Caching",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 5,
			required: false,
			defaultValue: 'on',
			helpText: 'Marks the system prompt and tool definitions as cacheable. Strongly recommended for MCP-backed Agents, where the same large tool catalog is resent on every turn.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.thinkingEnabled",
			name: "Extended Thinking",
			fieldName: "thinkingEnabled",
			fieldLabel: "Enable Extended Thinking",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 6,
			required: false,
			helpText: 'Lets the model reason before answering. Slower and more expensive; temperature and top_p are ignored while enabled.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.thinkingBudgetTokens",
			name: "Thinking Budget",
			fieldName: "thinkingBudgetTokens",
			fieldLabel: "Thinking Budget (tokens)",
			fieldContext: "config",
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 7,
			required: false,
			defaultValue: DEFAULT_THINKING_BUDGET_TOKENS.toString(),
			helpText: 'Minimum 1024. Must stay below Max Output Tokens - the provider raises max_tokens automatically if needed.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.longContext",
			name: "1M Context Window",
			fieldName: "longContext",
			fieldLabel: "Enable 1M Token Context (beta)",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 8,
			required: false,
			helpText: 'Sends the context-1m beta header. Only supported on Sonnet 4.5 and newer, and priced at a premium above 200k input tokens.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.samplingParams",
			name: "Sampling Parameters",
			fieldName: "samplingParams",
			fieldLabel: "Send temperature and top_p",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 9,
			required: false,
			helpText: 'Off by default. Newer Claude models reject temperature with "400 `temperature` is deprecated for this model", and Morpheus supplies one on every chat request. Only enable this against models that still accept sampling parameters.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.usageFooter",
			name: "Token Usage Footer",
			fieldName: "usageFooter",
			fieldLabel: "Append token usage to answers",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 10,
			required: false,
			helpText: 'Adds an italic line with cached, input and output token counts to the end of each final answer. Morpheus does not display token usage anywhere in the chat, so this is the only way to see the prompt cache working without reading the appliance log. Intermediate tool-call turns are left untouched.'
		)

		return optionTypes
	}

	@Override
	ServiceResponse validate(LlmIntegration llmIntegration, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if (!accountIntegration) {
				return ServiceResponse.error('Account integration is required for the Anthropic integration')
			}
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				return ServiceResponse.error('An Anthropic API key is required')
			}
			def result = apiService.listModels(resolveBaseUrl(accountIntegration), apiKey, resolveApiVersion(accountIntegration))
			if (result.success) {
				return ServiceResponse.success(llmIntegration)
			}
			return ServiceResponse.error(result.msg ?: 'Failed to verify the Anthropic connection')
		} catch (Exception e) {
			log.error("Error verifying Anthropic integration: ${e.message}", e)
			return ServiceResponse.error("Verification failed: ${e.message}")
		}
	}

	@Override
	void refresh(LlmIntegration llmIntegration) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			log.warn('Cannot refresh Anthropic integration - account integration is missing')
			return
		}
		try {
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				log.warn('Cannot refresh Anthropic integration - no API key configured')
				return
			}
			String baseUrl = resolveBaseUrl(accountIntegration)
			String apiVersion = resolveApiVersion(accountIntegration)
			new LlmModelsSync(morpheusContext, llmIntegration, PROVIDER_CODE, apiService)
				.execute(baseUrl, apiKey, apiVersion) { Map apiResponse ->
					buildModelsFromApiResponse(llmIntegration, apiResponse)
				}
			new LlmUsageSync(llmIntegration, morpheusContext)
				.execute(apiService, baseUrl, apiKey, apiVersion, resolveUsageProbeModel(llmIntegration))
		} catch (Exception e) {
			log.error("Error refreshing Anthropic integration: ${e.message}", e)
		}
	}

	@Override
	ServiceResponse<LlmChatResponse> generateResponse(LlmIntegration llmIntegration, LlmChatRequest request, Map opts) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			return ServiceResponse.error('Account integration is required for the Anthropic integration')
		}
		String apiKey = resolveApiKey(accountIntegration)
		String baseUrl = resolveBaseUrl(accountIntegration)
		String apiVersion = resolveApiVersion(accountIntegration)
		Map requestBody = buildMessagesRequestBody(request, accountIntegration, false)

		int maxAttempts = 3
		Exception lastException = null
		Map requestOpts = opts ?: [:]
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			if (attempt > 1) {
				log.warn("Anthropic API retry ${attempt - 1}/${maxAttempts - 1} after connection failure")
				Thread.sleep(1500L * (attempt - 1))
			}
			try {
				def result = apiService.createMessage(baseUrl, apiKey, requestBody, apiVersion, resolveBetas(accountIntegration), requestOpts)
				if (result.success && result.data) {
					return ServiceResponse.success(
						appendUsageFooter(parseMessageResponse(result.data as Map), accountIntegration))
				}
				return ServiceResponse.error(result.msg ?: 'Chat completion failed')
			} catch (Exception e) {
				lastException = e
				if (isRetryableError(e) && attempt < maxAttempts) {
					log.warn("Anthropic connection error (attempt ${attempt}): ${e.message}")
					continue
				}
				log.error("Error during Anthropic chat completion: ${e.message}", e)
				return ServiceResponse.error("Chat completion failed: ${e.message}")
			}
		}
		return ServiceResponse.error("Chat completion failed after ${maxAttempts} attempts: ${lastException?.message}")
	}

	@Override
	void streamResponse(LlmIntegration llmIntegration, LlmChatRequest request, LlmStreamingResponseHandler handler, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if (!accountIntegration) {
				handler?.onError(new IllegalArgumentException('Account integration is required for the Anthropic integration'))
				return
			}
			String apiKey = resolveApiKey(accountIntegration)
			String baseUrl = resolveBaseUrl(accountIntegration)
			String apiVersion = resolveApiVersion(accountIntegration)
			Map requestBody = buildMessagesRequestBody(request, accountIntegration, true)

			Map result = apiService.streamMessage(baseUrl, apiKey, requestBody, apiVersion, resolveBetas(accountIntegration), { String chunk ->
				handler?.onPartialResponse(chunk)
			}, opts ?: [:])

			if (result?.success && result.data) {
				handler?.onCompleteResponse(
					appendUsageFooter(parseMessageResponse(result.data as Map), accountIntegration))
			} else {
				handler?.onError(new RuntimeException(result?.msg ?: 'Streaming chat completion failed'))
			}
		} catch (Exception e) {
			log.error("Error during Anthropic streaming chat: ${e.message}", e)
			handler?.onError(e)
		}
	}

	// ------------------------------------------------------------------
	// Request translation: Morpheus (OpenAI-shaped) -> Anthropic Messages
	// ------------------------------------------------------------------

	/**
	 * Build a /v1/messages request body from an LlmChatRequest.
	 *
	 * Handles the three structural differences to the OpenAI schema:
	 *  - system prompts are a top-level field, not a message
	 *  - assistant tool calls become tool_use content blocks
	 *  - tool results become tool_result blocks inside a user message
	 */
	protected Map buildMessagesRequestBody(LlmChatRequest request, AccountIntegration accountIntegration, Boolean stream) {
		List<String> systemParts = []
		List<Map> messages = []

		request.messages?.each { LlmChatMessage msg ->
			String role = msg.role?.toLowerCase() ?: 'user'
			Map metadata = msg.metadata ?: [:]

			if (role == 'system' || role == 'developer') {
				if (msg.content) {
					systemParts << msg.content.toString()
				}
				return
			}

			if (role == 'tool' || metadata.tool_call_id) {
				Map toolResult = [
					type       : 'tool_result',
					tool_use_id: metadata.tool_call_id?.toString(),
					content    : msg.content?.toString() ?: ''
				]
				// Anthropic requires tool_result blocks to sit in a user message and
				// expects every result of one assistant turn in a single message.
				Map previous = messages ? messages[-1] : null
				if (previous && previous.role == 'user' && previous.content instanceof List &&
					(previous.content as List).every { it instanceof Map && it.type == 'tool_result' }) {
					(previous.content as List) << toolResult
				} else {
					messages << [role: 'user', content: [toolResult]]
				}
				return
			}

			if (role == 'assistant' && metadata.tool_calls) {
				List<Map> blocks = []
				if (msg.content) {
					blocks << [type: 'text', text: msg.content.toString()]
				}
				(metadata.tool_calls as List).each { toolCall ->
					Map call = toolCall as Map
					Map function = call.function instanceof Map ? call.function as Map : [:]
					blocks << [
						type : 'tool_use',
						id   : call.id?.toString() ?: UUID.randomUUID().toString(),
						name : function.name?.toString(),
						input: parseToolArguments(function.arguments)
					]
				}
				messages << [role: 'assistant', content: blocks]
				return
			}

			messages << [role: role == 'assistant' ? 'assistant' : 'user', content: msg.content?.toString() ?: '']
		}

		boolean cachingEnabled = isPromptCachingEnabled(accountIntegration)
		boolean thinkingEnabled = isThinkingEnabled(accountIntegration)

		Integer maxTokens = request.maxOutputTokens ?: resolveDefaultMaxOutputTokens(accountIntegration)
		Map requestBody = [
			model     : request.model ?: DEFAULT_CHAT_MODEL,
			max_tokens: maxTokens,
			messages  : messages
		]

		if (systemParts) {
			String systemText = systemParts.join('\n\n')
			// The system prompt is the most stable prefix of an Agent conversation,
			// so it gets the first cache breakpoint when caching is on.
			requestBody.system = cachingEnabled ?
				[[type: 'text', text: systemText, cache_control: [type: 'ephemeral']]] :
				systemText
		}

		if (thinkingEnabled) {
			Integer budget = resolveThinkingBudget(accountIntegration)
			// max_tokens has to leave room for the answer on top of the thinking budget.
			if (maxTokens <= budget) {
				maxTokens = budget + DEFAULT_MAX_OUTPUT_TOKENS
				requestBody.max_tokens = maxTokens
			}
			requestBody.thinking = [type: 'enabled', budget_tokens: budget]
			// temperature/top_p are rejected while thinking is enabled.
		} else if (isSamplingParamsEnabled(accountIntegration)) {
			// Opt-in only. Morpheus sends a temperature on every chat request, and
			// newer Claude models reject it outright, which surfaces in the UI as the
			// misleading "The AI model is no longer available".
			if (request.temperature != null) {
				requestBody.temperature = request.temperature
			}
			if (request.topP != null) {
				requestBody.top_p = request.topP
			}
		}

		if (request.stopSequences) {
			requestBody.stop_sequences = request.stopSequences
		}

		List<Map> tools = convertTools(request.options?.tools)
		if (tools) {
			if (cachingEnabled) {
				// A cache_control marker on the final tool caches the whole tool block.
				// This is the big win for MCP agents: the tool catalog is identical on
				// every turn but would otherwise be re-billed as fresh input each time.
				Map lastTool = new LinkedHashMap(tools[-1])
				lastTool.cache_control = [type: 'ephemeral']
				tools = tools[0..<tools.size() - 1] + [lastTool]
			}
			requestBody.tools = tools
			Map toolChoice = convertToolChoice(request.options?.tool_choice)
			if (toolChoice) {
				requestBody.tool_choice = toolChoice
			}
		}

		if (stream != null) {
			requestBody.stream = stream
		}
		return requestBody
	}

	/**
	 * OpenAI tool definitions -> Anthropic tool definitions.
	 * [{type:function, function:{name, description, parameters}}]
	 *   -> [{name, description, input_schema}]
	 */
	protected List<Map> convertTools(def tools) {
		if (!(tools instanceof List)) {
			return []
		}
		List<Map> converted = []
		tools.each { tool ->
			if (!(tool instanceof Map)) {
				return
			}
			Map toolMap = tool as Map
			// Already in Anthropic shape - pass through untouched.
			if (toolMap.input_schema != null && toolMap.name != null) {
				converted << new LinkedHashMap(toolMap)
				return
			}
			Map function = toolMap.function instanceof Map ? toolMap.function as Map : toolMap
			String name = function.name?.toString()
			if (!name) {
				return
			}
			Map schema = function.parameters instanceof Map ? new LinkedHashMap(function.parameters as Map) : [type: 'object', properties: [:]]
			if (!schema.type) {
				schema.type = 'object'
			}
			Map anthropicTool = [name: name, input_schema: schema]
			if (function.description) {
				anthropicTool.description = function.description.toString()
			}
			converted << anthropicTool
		}
		return converted
	}

	/**
	 * OpenAI tool_choice -> Anthropic tool_choice.
	 * 'auto'|'none'|'required'|{type:function, function:{name}}
	 */
	protected Map convertToolChoice(def toolChoice) {
		if (toolChoice == null) {
			return null
		}
		if (toolChoice instanceof CharSequence) {
			switch (toolChoice.toString().toLowerCase()) {
				case 'auto': return [type: 'auto']
				case 'required': return [type: 'any']
				case 'none': return [type: 'none']
				default: return [type: 'auto']
			}
		}
		if (toolChoice instanceof Map) {
			Map choice = toolChoice as Map
			if (choice.type == 'auto' || choice.type == 'any' || choice.type == 'none') {
				return new LinkedHashMap(choice)
			}
			Map function = choice.function instanceof Map ? choice.function as Map : [:]
			String name = function.name?.toString() ?: choice.name?.toString()
			if (name) {
				return [type: 'tool', name: name]
			}
		}
		return null
	}

	/**
	 * Tool arguments arrive as a JSON string in the OpenAI convention; Anthropic
	 * expects a real object.
	 */
	protected Map parseToolArguments(def arguments) {
		if (arguments == null) {
			return [:]
		}
		if (arguments instanceof Map) {
			return new LinkedHashMap(arguments as Map)
		}
		String raw = arguments.toString().trim()
		if (!raw) {
			return [:]
		}
		try {
			def parsed = new JsonSlurper().parseText(raw)
			return parsed instanceof Map ? new LinkedHashMap(parsed as Map) : [:]
		} catch (Exception ignored) {
			log.warn("Could not parse tool arguments as JSON: ${raw}")
			return [:]
		}
	}

	// ------------------------------------------------------------------
	// Response translation: Anthropic Messages -> Morpheus (OpenAI-shaped)
	// ------------------------------------------------------------------

	/**
	 * Turn a /v1/messages response (or an accumulated stream) into an
	 * LlmChatResponse, mapping tool_use blocks back onto the OpenAI-shaped
	 * tool_calls metadata Morpheus expects.
	 */
	protected LlmChatResponse parseMessageResponse(Map data) {
		LlmChatResponse response = new LlmChatResponse()
		response.id = data.id?.toString()
		response.model = data.model?.toString()
		response.finishReason = mapStopReason(data.stop_reason?.toString())

		StringBuilder text = new StringBuilder()
		StringBuilder thinking = new StringBuilder()
		List<Map> toolCalls = []
		def content = data.content
		if (content instanceof List) {
			content.each { block ->
				if (!(block instanceof Map)) {
					return
				}
				Map blockMap = block as Map
				if (blockMap.type == 'text' && blockMap.text != null) {
					text.append(blockMap.text.toString())
				} else if (blockMap.type == 'thinking' || blockMap.type == 'redacted_thinking') {
					String thinkingText = blockMap.thinking?.toString()
					if (thinkingText) {
						thinking.append(thinking.length() > 0 ? '\n' : '').append(thinkingText)
					}
				} else if (blockMap.type == 'tool_use') {
					toolCalls << [
						id      : blockMap.id?.toString() ?: UUID.randomUUID().toString(),
						type    : 'function',
						function: [
							name     : blockMap.name?.toString(),
							arguments: new JsonBuilder(blockMap.input ?: [:]).toString()
						]
					]
				}
			}
		} else if (content instanceof CharSequence) {
			text.append(content.toString())
		}

		LlmChatMessage message = new LlmChatMessage()
		message.role = data.role?.toString() ?: 'assistant'
		message.content = text.toString()
		response.message = message

		if (thinking.length() > 0) {
			response.metadata.put('thinking', thinking.toString())
		}

		if (toolCalls) {
			response.metadata.put('tool_calls', toolCalls)
			if (message.metadata == null) {
				message.metadata = [:]
			}
			message.metadata.put('tool_calls', toolCalls)
		}

		def usage = data.usage
		if (usage instanceof Map) {
			Map usageMap = usage as Map
			LlmTokenUsage tokenUsage = new LlmTokenUsage()
			Integer inputTokens = toInteger(usageMap.input_tokens)
			Integer outputTokens = toInteger(usageMap.output_tokens)
			tokenUsage.inputTokens = inputTokens
			tokenUsage.outputTokens = outputTokens
			// Cached prefix tokens are billed separately and are not part of
			// input_tokens, so they are added in to keep the total honest.
			Integer cacheRead = toInteger(usageMap.cache_read_input_tokens)
			Integer cacheWrite = toInteger(usageMap.cache_creation_input_tokens)
			if (inputTokens != null || outputTokens != null || cacheRead != null || cacheWrite != null) {
				tokenUsage.totalTokens = (inputTokens ?: 0) + (outputTokens ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
			}
			response.tokenUsage = tokenUsage
			if (cacheRead != null || cacheWrite != null) {
				response.metadata.put('cache_read_input_tokens', cacheRead ?: 0)
				response.metadata.put('cache_creation_input_tokens', cacheWrite ?: 0)
				// Morpheus does not surface response metadata anywhere in the UI, so
				// without this line prompt caching - the reason this plugin exists -
				// cannot be observed on a running appliance.
				log.info("Anthropic prompt cache: read=${cacheRead ?: 0} created=${cacheWrite ?: 0} " +
					"uncached_input=${inputTokens ?: 0} output=${outputTokens ?: 0}")
			}
		}

		return response
	}

	/**
	 * Anthropic stop_reason -> OpenAI finish_reason, so downstream Morpheus code
	 * (which branches on 'tool_calls') keeps working.
	 */
	protected String mapStopReason(String stopReason) {
		if (!stopReason) {
			return null
		}
		switch (stopReason) {
			case 'end_turn': return 'stop'
			case 'stop_sequence': return 'stop'
			case 'max_tokens': return 'length'
			case 'tool_use': return 'tool_calls'
			case 'pause_turn': return 'stop'
			case 'refusal': return 'content_filter'
			default: return stopReason
		}
	}

	// ------------------------------------------------------------------
	// Model catalog
	// ------------------------------------------------------------------

	protected List<LlmModel> buildModelsFromApiResponse(LlmIntegration llmIntegration, Map apiResponse) {
		boolean longContext = isLongContextEnabled(llmIntegration?.accountIntegration)
		List<LlmModel> models = []
		def modelData = apiResponse?.data
		if (modelData instanceof List) {
			modelData.each { entry ->
				if (!(entry instanceof Map)) {
					return
				}
				Map entryMap = entry as Map
				String modelId = entryMap.id?.toString()
				if (!modelId || !modelId.startsWith('claude')) {
					return
				}
				LlmModel model = new LlmModel()
				model.code = modelId
				model.externalId = modelId
				model.name = entryMap.display_name?.toString() ?: formatModelName(modelId)
				model.providerCode = PROVIDER_CODE
				model.modelType = 'chat'
				model.contextWindow = estimateContextWindow(modelId, longContext)
				model.maxOutputTokens = estimateMaxOutputTokens(modelId)
				model.llmIntegration = llmIntegration
				model.enabled = true
				model.metadata = [
					supportsToolUse    : true,
					supportsStreaming  : true,
					supportsVision     : true,
					supportsPromptCache: true,
					apiFormat          : 'anthropic-messages'
				]
				models.add(model)
			}
		}
		models.sort { a, b -> (a.name ?: '').compareTo(b.name ?: '') }
		return models
	}

	protected String formatModelName(String modelId) {
		return modelId.split('-').collect { it.capitalize() }.join(' ')
	}

	/**
	 * Claude models expose a 200k token context by default. Sonnet 4.5 and newer
	 * can be extended to 1M via the context-1m beta header; that larger window is
	 * only reported when the integration actually enables it.
	 */
	protected Long estimateContextWindow(String modelId, boolean longContext = false) {
		if (longContext && supportsLongContext(modelId)) {
			return LONG_CONTEXT_WINDOW
		}
		return STANDARD_CONTEXT_WINDOW
	}

	protected boolean supportsLongContext(String modelId) {
		String id = modelId?.toLowerCase() ?: ''
		return id.contains('sonnet-4-5') || id.contains('sonnet-4-6') || id.contains('sonnet-5') || id.contains('opus-5')
	}

	protected Long estimateMaxOutputTokens(String modelId) {
		String id = modelId?.toLowerCase() ?: ''
		if (id.contains('haiku-4') || id.contains('sonnet-4')) {
			return 64000L
		}
		if (id.contains('opus-4') || id.contains('opus-5')) {
			return 32000L
		}
		if (id.contains('3-7-sonnet')) {
			return 64000L
		}
		if (id.contains('haiku')) {
			return 8192L
		}
		return 8192L
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	protected String resolveApiKey(AccountIntegration accountIntegration) {
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	protected String resolveBaseUrl(AccountIntegration accountIntegration) {
		String url = accountIntegration?.serviceUrl?.trim()
		if (!url) {
			return DEFAULT_API_URL
		}
		// Tolerate someone pasting the full messages URL into the endpoint field.
		return url.replaceAll('/+$', '').replaceAll('/v1/messages$', '').replaceAll('/v1$', '')
	}

	protected String resolveApiVersion(AccountIntegration accountIntegration) {
		def configured = accountIntegration?.getConfigProperty('apiVersion')
		return configured?.toString()?.trim() ?: AnthropicApiService.DEFAULT_API_VERSION
	}

	protected Integer resolveDefaultMaxOutputTokens(AccountIntegration accountIntegration) {
		def configured = accountIntegration?.getConfigProperty('maxOutputTokens')
		Integer parsed = toInteger(configured)
		return (parsed != null && parsed > 0) ? parsed : DEFAULT_MAX_OUTPUT_TOKENS
	}

	/** Checkbox config values arrive as 'on'/'true'/true depending on the caller. */
	protected static boolean toBoolean(def value, boolean defaultValue) {
		if (value == null) {
			return defaultValue
		}
		if (value instanceof Boolean) {
			return (Boolean) value
		}
		String raw = value.toString().trim().toLowerCase()
		if (!raw) {
			return defaultValue
		}
		return raw in ['on', 'true', 'yes', '1']
	}

	protected boolean isPromptCachingEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('promptCaching'), true)
	}

	protected boolean isThinkingEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('thinkingEnabled'), false)
	}

	protected boolean isLongContextEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('longContext'), false)
	}

	/**
	 * Off by default: Morpheus supplies a temperature on every chat request, and
	 * newer Claude models answer that with
	 * "400 `temperature` is deprecated for this model".
	 */
	protected boolean isSamplingParamsEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('samplingParams'), false)
	}

	protected boolean isUsageFooterEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('usageFooter'), false)
	}

	/**
	 * Appends an italic token summary to a final answer.
	 *
	 * Only final answers are touched. An agent turn that ends in tool_use is
	 * sent back to Anthropic as conversation history on the next request, so a
	 * footer there would end up in the model's own context - and be billed - on
	 * every subsequent turn.
	 */
	protected LlmChatResponse appendUsageFooter(LlmChatResponse response, AccountIntegration accountIntegration) {
		if (!isUsageFooterEnabled(accountIntegration) || response?.message?.content == null) {
			return response
		}
		if (response.finishReason == 'tool_calls' || !response.message.content.toString().trim()) {
			return response
		}
		LlmTokenUsage usage = response.tokenUsage
		if (!usage) {
			return response
		}
		List<String> parts = []
		Integer cached = toInteger(response.metadata?.get('cache_read_input_tokens'))
		if (cached) {
			parts << "${formatTokenCount(cached)} cached"
		}
		if (usage.inputTokens != null) {
			parts << "${formatTokenCount(usage.inputTokens)} input"
		}
		if (usage.outputTokens != null) {
			parts << "${formatTokenCount(usage.outputTokens)} output"
		}
		if (!parts) {
			return response
		}
		// ASCII only. The footer becomes part of the conversation history that
		// Morpheus replays to Anthropic on the next turn, and its storage path
		// mangles non-ASCII on the way through - a U+21B3 arrow and a U+00B7
		// separator came back as unpaired surrogates and the follow-up request
		// died with "400 ... str is not valid UTF-8: surrogates not allowed".
		// Italics only. The Morpheus chat renderer escapes raw HTML rather than
		// stripping it, so a <sub> wrapper for smaller type shows up as literal
		// tags in the answer. Markdown itself has no notion of type size.
		response.message.content = "${response.message.content}\n\n*Tokens: ${parts.join(', ')}*"
		return response
	}

	protected static String formatTokenCount(Integer value) {
		return String.format(Locale.US, '%,d', value ?: 0)
	}

	protected Integer resolveThinkingBudget(AccountIntegration accountIntegration) {
		Integer configured = toInteger(accountIntegration?.getConfigProperty('thinkingBudgetTokens'))
		Integer budget = (configured != null && configured > 0) ? configured : DEFAULT_THINKING_BUDGET_TOKENS
		return Math.max(budget, MIN_THINKING_BUDGET_TOKENS)
	}

	/** Beta headers the integration has opted into. */
	protected List<String> resolveBetas(AccountIntegration accountIntegration) {
		List<String> betas = []
		if (isLongContextEnabled(accountIntegration)) {
			betas << AnthropicApiService.LONG_CONTEXT_BETA
		}
		return betas
	}

	/**
	 * Cheapest enabled model for the rate limit probe, so refreshing usage costs
	 * as close to nothing as possible.
	 */
	protected String resolveUsageProbeModel(LlmIntegration llmIntegration) {
		List<LlmModel> models = llmIntegration?.models ?: []
		LlmModel haiku = models.find { it?.enabled != false && it?.code?.toLowerCase()?.contains('haiku') }
		return haiku?.code ?: models.find { it?.enabled != false }?.code ?: 'claude-haiku-4-5'
	}

	protected static Integer toInteger(def value) {
		if (value == null) {
			return null
		}
		if (value instanceof Number) {
			return ((Number) value).intValue()
		}
		try {
			String raw = value.toString().trim()
			return raw ? Integer.parseInt(raw) : null
		} catch (Exception ignored) {
			return null
		}
	}

	protected boolean isRetryableError(Exception e) {
		String msg = e?.message?.toLowerCase() ?: ''
		return msg.contains('failed to respond') || msg.contains('connection') || msg.contains('reset') ||
			msg.contains('broken pipe') || msg.contains('socket') || msg.contains('nohttpresponse') ||
			msg.contains('stream closed') || msg.contains('timeout')
	}
}
