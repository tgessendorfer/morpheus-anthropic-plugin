package com.morpheusdata.anthropic

import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatRequest
import com.morpheusdata.model.llm.LlmChatResponse
import spock.lang.Specification

/**
 * Covers the translation layer between the OpenAI-shaped conventions Morpheus
 * uses internally and the native Anthropic Messages API.
 */
class AnthropicProviderSpec extends Specification {

	AnthropicProvider provider = new AnthropicProvider(null, null)
	AccountIntegration integration = new AccountIntegration(serviceUrl: 'https://api.anthropic.com')

	private static LlmChatMessage message(String role, String content, Map metadata = null) {
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = role
		msg.content = content
		msg.metadata = metadata
		return msg
	}

	def "system messages are hoisted into the top level system field"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-4-6',
			messages: [
				message('system', 'You are a Morpheus operator.'),
				message('system', 'Always be terse.'),
				message('user', 'List my instances')
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'all system turns are concatenated into the single system field'
		body.system[0].text == 'You are a Morpheus operator.\n\nAlways be terse.'
		body.messages.size() == 1
		body.messages[0].role == 'user'
		body.max_tokens == AnthropicProvider.DEFAULT_MAX_OUTPUT_TOKENS
	}

	def "assistant tool_calls become tool_use blocks with parsed input"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [
				message('user', 'How many VMs?'),
				message('assistant', 'Let me check.', [
					tool_calls: [[
						id      : 'toolu_01',
						type    : 'function',
						function: [name: 'list_instances', arguments: '{"max":25}']
					]]
				])
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)
		def assistant = body.messages[1]

		then:
		assistant.role == 'assistant'
		assistant.content[0].type == 'text'
		assistant.content[1].type == 'tool_use'
		assistant.content[1].id == 'toolu_01'
		assistant.content[1].name == 'list_instances'
		assistant.content[1].input == [max: 25]
	}

	def "consecutive tool results are merged into a single user message"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [
				message('user', 'Check both clouds'),
				message('assistant', null, [tool_calls: [
					[id: 'toolu_a', type: 'function', function: [name: 'list_clouds', arguments: '{}']],
					[id: 'toolu_b', type: 'function', function: [name: 'list_zones', arguments: '{}']]
				]]),
				message('tool', '{"clouds":2}', [tool_call_id: 'toolu_a']),
				message('tool', '{"zones":5}', [tool_call_id: 'toolu_b'])
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then:
		body.messages.size() == 3
		body.messages[2].role == 'user'
		body.messages[2].content.size() == 2
		body.messages[2].content*.type.every { it == 'tool_result' }
		body.messages[2].content*.tool_use_id == ['toolu_a', 'toolu_b']
	}

	def "OpenAI tool definitions are converted to the Anthropic schema"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('user', 'hi')],
			options: [
				tools      : [[
					type    : 'function',
					function: [
						name       : 'use_instances_tools',
						description: 'Load the instance tool group',
						parameters : [type: 'object', properties: [scope: [type: 'string']]]
					]
				]],
				tool_choice: 'auto'
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then:
		body.tools.size() == 1
		body.tools[0].name == 'use_instances_tools'
		body.tools[0].description == 'Load the instance tool group'
		body.tools[0].input_schema.type == 'object'
		body.tools[0].containsKey('function') == false
		body.tool_choice == [type: 'auto']
	}

	def "tool_choice required maps to any and a named tool maps to tool"() {
		expect:
		provider.convertToolChoice('required') == [type: 'any']
		provider.convertToolChoice('none') == [type: 'none']
		provider.convertToolChoice([type: 'function', function: [name: 'get_appliance_health']]) == [type: 'tool', name: 'get_appliance_health']
	}

	def "tool_use response blocks map back onto OpenAI-shaped tool_calls"() {
		given:
		Map apiResponse = [
			id         : 'msg_123',
			model      : 'claude-sonnet-4-6',
			role       : 'assistant',
			stop_reason: 'tool_use',
			content    : [
				[type: 'text', text: 'Checking your instances.'],
				[type: 'tool_use', id: 'toolu_99', name: 'list_instances', input: [max: 10]]
			],
			usage      : [input_tokens: 1200, output_tokens: 80]
		]

		when:
		LlmChatResponse response = provider.parseMessageResponse(apiResponse)

		then:
		response.id == 'msg_123'
		response.finishReason == 'tool_calls'
		response.message.content == 'Checking your instances.'
		response.metadata.tool_calls.size() == 1
		response.metadata.tool_calls[0].id == 'toolu_99'
		response.metadata.tool_calls[0].type == 'function'
		response.metadata.tool_calls[0].function.name == 'list_instances'
		response.metadata.tool_calls[0].function.arguments == '{"max":10}'
		response.tokenUsage.inputTokens == 1200
		response.tokenUsage.totalTokens == 1280
	}

	def "stop reasons map to OpenAI finish reasons"() {
		expect:
		provider.mapStopReason(anthropic) == openai

		where:
		anthropic       || openai
		'end_turn'      || 'stop'
		'max_tokens'    || 'length'
		'tool_use'      || 'tool_calls'
		'stop_sequence' || 'stop'
		null            || null
	}

	def "base url tolerates a full messages endpoint being pasted in"() {
		expect:
		provider.resolveBaseUrl(new AccountIntegration(serviceUrl: url)) == 'https://api.anthropic.com'

		where:
		url << [
			'https://api.anthropic.com',
			'https://api.anthropic.com/',
			'https://api.anthropic.com/v1',
			'https://api.anthropic.com/v1/messages'
		]
	}

	private static AccountIntegration configured(Map config) {
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://api.anthropic.com')
		ai.setConfigMap(config)
		return ai
	}

	def "prompt caching marks the system prompt and the last tool by default"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('system', 'You are a Morpheus operator.'), message('user', 'hi')],
			options: [tools: [
				[type: 'function', function: [name: 'use_instances_tools', parameters: [type: 'object']]],
				[type: 'function', function: [name: 'use_clouds_tools', parameters: [type: 'object']]]
			]]
		)

		when: 'caching is left at its default (on)'
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'the system prompt becomes a cacheable block'
		body.system instanceof List
		body.system[0].type == 'text'
		body.system[0].cache_control == [type: 'ephemeral']

		and: 'only the final tool carries the breakpoint, which caches the whole block'
		body.tools.size() == 2
		body.tools[0].cache_control == null
		body.tools[1].cache_control == [type: 'ephemeral']
	}

	def "prompt caching can be switched off"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('system', 'Be terse.'), message('user', 'hi')],
			options: [tools: [[type: 'function', function: [name: 'ping', parameters: [type: 'object']]]]]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, configured([promptCaching: 'off']), false)

		then:
		body.system == 'Be terse.'
		body.tools[0].cache_control == null
	}

	def "extended thinking adds the thinking block and drops sampling params"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('user', 'Plan the migration')],
			temperature: 0.7d,
			topP: 0.9d
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, configured([thinkingEnabled: 'on', thinkingBudgetTokens: '10000']), false)

		then:
		body.thinking == [type: 'enabled', budget_tokens: 10000]
		!body.containsKey('temperature')
		!body.containsKey('top_p')

		and: 'max_tokens is raised above the budget so an answer still fits'
		body.max_tokens > 10000
	}

	def "the thinking budget is clamped to the API minimum"() {
		expect:
		provider.resolveThinkingBudget(configured([thinkingBudgetTokens: '100'])) == AnthropicProvider.MIN_THINKING_BUDGET_TOKENS
		provider.resolveThinkingBudget(integration) == AnthropicProvider.DEFAULT_THINKING_BUDGET_TOKENS
	}

	def "the 1M beta header is only sent when enabled"() {
		expect:
		provider.resolveBetas(integration) == []
		provider.resolveBetas(configured([longContext: 'on'])) == [AnthropicApiService.LONG_CONTEXT_BETA]
	}

	def "the long context window is only reported for models that support it"() {
		expect:
		provider.estimateContextWindow('claude-sonnet-4-6', longContext) == window

		where:
		longContext || window
		false       || AnthropicProvider.STANDARD_CONTEXT_WINDOW
		true        || AnthropicProvider.LONG_CONTEXT_WINDOW
	}

	def "an older model keeps the standard window even with the beta enabled"() {
		expect:
		provider.estimateContextWindow('claude-3-5-haiku-latest', true) == AnthropicProvider.STANDARD_CONTEXT_WINDOW
	}

	def "thinking blocks and cache usage are surfaced in the response"() {
		given:
		Map apiResponse = [
			id         : 'msg_9',
			model      : 'claude-sonnet-4-6',
			stop_reason: 'end_turn',
			content    : [
				[type: 'thinking', thinking: 'The user wants a count.'],
				[type: 'text', text: 'You have 12 instances.']
			],
			usage      : [input_tokens: 120, output_tokens: 40, cache_read_input_tokens: 18000, cache_creation_input_tokens: 0]
		]

		when:
		LlmChatResponse response = provider.parseMessageResponse(apiResponse)

		then:
		response.message.content == 'You have 12 instances.'
		response.metadata.thinking == 'The user wants a count.'
		response.metadata.cache_read_input_tokens == 18000
		response.tokenUsage.totalTokens == 18160
	}

	def "configured max output tokens override the default"() {
		given:
		AccountIntegration withConfig = configured([maxOutputTokens: '16384'])

		expect:
		provider.resolveDefaultMaxOutputTokens(withConfig) == 16384
		provider.resolveDefaultMaxOutputTokens(integration) == AnthropicProvider.DEFAULT_MAX_OUTPUT_TOKENS
	}
}
