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

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.util.EntityUtils

import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client service for the Anthropic Claude API.
 *
 * Unlike OpenAI-shaped providers this talks the native Messages API:
 *  - authentication via the {@code x-api-key} header (not a Bearer token)
 *  - a mandatory {@code anthropic-version} header
 *  - POST /v1/messages for both streaming and non-streaming completions
 *
 * Works against api.anthropic.com as well as any Anthropic-compatible gateway
 * (LiteLLM in anthropic passthrough mode, a corporate egress proxy, ...).
 */
@Slf4j
class AnthropicApiService {

	static final String MESSAGES_PATH = '/v1/messages'
	static final String MODELS_PATH = '/v1/models'
	static final String API_KEY_HEADER = 'x-api-key'
	static final String VERSION_HEADER = 'anthropic-version'
	static final String BETA_HEADER = 'anthropic-beta'
	static final String DEFAULT_API_VERSION = '2023-06-01'
	static final String LONG_CONTEXT_BETA = 'context-1m-2025-08-07'
	static final String CLIENT_SCOPE_KEY = 'clientScopeKey'
	static final Integer DEFAULT_CONNECTION_TIMEOUT = 30000
	static final Integer DEFAULT_READ_TIMEOUT = 30000
	static final Integer DEFAULT_INTERACT_READ_TIMEOUT = 300000
	static final Long SESSION_CLIENT_TTL_MS = 60L * 60L * 1000L

	protected final ConcurrentHashMap<String, SessionClientHolder> sessionClients = new ConcurrentHashMap<>()

	protected static class SessionClientHolder {
		final HttpApiClient apiClient
		volatile long lastUsedAt

		SessionClientHolder(HttpApiClient apiClient, long lastUsedAt) {
			this.apiClient = apiClient
			this.lastUsedAt = lastUsedAt
		}
	}

	/**
	 * List the models the given API key may use. Also doubles as the
	 * connectivity/credential check performed on integration save.
	 */
	Map listModels(String baseUrl, String apiKey, String apiVersion = DEFAULT_API_VERSION) {
		// limit has to travel as a query parameter: HttpApiClient percent-encodes
		// the path it is given, so a '?' inside it would be sent as %3F and the
		// request would come back 404.
		return executeGet(baseUrl, MODELS_PATH, apiKey, apiVersion, null, [:], [limit: '100'])
	}

	/**
	 * Minimal probe request used to read the current rate limit headers.
	 * max_tokens is 1 so the probe costs almost nothing.
	 */
	Map fetchUsageHeaders(String baseUrl, String apiKey, String apiVersion, String model) {
		Map requestBody = [
			model     : model ?: 'claude-haiku-4-5',
			max_tokens: 1,
			messages  : [[role: 'user', content: 'usage']]
		]
		return createMessage(baseUrl, apiKey, requestBody, apiVersion, null, [:])
	}

	/**
	 * Non-streaming completion against POST /v1/messages.
	 */
	Map createMessage(String baseUrl, String apiKey, Map requestBody, String apiVersion = DEFAULT_API_VERSION, List<String> betas = null, Map opts = [:]) {
		return executePost(baseUrl, MESSAGES_PATH, apiKey, requestBody, apiVersion, betas, opts)
	}

	/**
	 * Streaming completion against POST /v1/messages with SSE.
	 *
	 * The Anthropic event stream is block oriented rather than delta-of-choice
	 * oriented: text arrives as {@code content_block_delta/text_delta}, tool
	 * arguments arrive as {@code input_json_delta} fragments that have to be
	 * concatenated per content block index. The accumulated blocks are handed
	 * to the caller through the returned map so the provider can build the
	 * final LlmChatResponse (including tool calls) from them.
	 */
	Map streamMessage(String baseUrl, String apiKey, Map requestBody, String apiVersion, List<String> betas, Closure onText, Map opts = [:]) {
		CloseableHttpResponse response = null
		try {
			requestBody.stream = true
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, apiVersion, [
				'Content-Type': 'application/json',
				'Accept'      : 'text/event-stream'
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT, betas)

			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse<CloseableHttpResponse> apiResponse = apiClient.callStreamApi(baseUrl, MESSAGES_PATH, null, null, requestOptions, 'POST')
				response = apiResponse?.data
				if (apiResponse?.success != true || response == null) {
					String statusCode = apiResponse?.errorCode ?: response?.statusLine?.statusCode?.toString() ?: 'unknown'
					String errorBody = readErrorBody(response)
					return [success: false, msg: "Anthropic API returned ${statusCode}: ${errorBody ?: buildErrorMessage(apiResponse)}"]
				}
				return [success: true, data: consumeEventStream(response, onText)]
			}
		} catch (Exception e) {
			log.error("Error during Anthropic streaming completion: ${e.message}", e)
			return [success: false, msg: e.message]
		} finally {
			response?.close()
		}
	}

	/**
	 * Read an Anthropic SSE stream and accumulate it into a synthetic
	 * message payload shaped like a non-streaming /v1/messages response.
	 */
	protected Map consumeEventStream(CloseableHttpResponse response, Closure onText) {
		Map accumulated = [id: null, model: null, stop_reason: null, content: [], usage: [:]]
		Map<Integer, Map> blocks = [:]
		Map<Integer, StringBuilder> jsonBuffers = [:]
		groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()

		response.entity?.content?.withReader('UTF-8') { reader ->
			String line
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith('data:')) {
					continue
				}
				String payload = line.substring(5).trim()
				if (!payload || payload == '[DONE]') {
					continue
				}
				Map event
				try {
					event = slurper.parseText(payload) as Map
				} catch (Exception ignored) {
					log.debug("Skipping unparseable SSE payload: ${payload}")
					continue
				}
				switch (event.type?.toString()) {
					case 'message_start':
						Map message = event.message instanceof Map ? event.message as Map : [:]
						accumulated.id = message.id
						accumulated.model = message.model
						if (message.usage instanceof Map) {
							accumulated.usage.putAll(message.usage as Map)
						}
						break
					case 'content_block_start':
						Integer index = event.index as Integer
						Map block = event.content_block instanceof Map ? new LinkedHashMap(event.content_block as Map) : [:]
						if (block.type == 'text' && block.text == null) {
							block.text = ''
						}
						blocks.put(index, block)
						jsonBuffers.put(index, new StringBuilder())
						break
					case 'content_block_delta':
						Integer index = event.index as Integer
						Map block = blocks.get(index)
						Map delta = event.delta instanceof Map ? event.delta as Map : [:]
						if (block == null) {
							block = [type: delta.type == 'input_json_delta' ? 'tool_use' : 'text', text: '']
							blocks.put(index, block)
							jsonBuffers.put(index, new StringBuilder())
						}
						if (delta.type == 'text_delta' && delta.text != null) {
							String chunk = delta.text.toString()
							block.text = (block.text ?: '') + chunk
							onText?.call(chunk)
						} else if (delta.type == 'input_json_delta' && delta.partial_json != null) {
							jsonBuffers.get(index)?.append(delta.partial_json.toString())
						} else if (delta.type == 'thinking_delta' && delta.thinking != null) {
							block.thinking = (block.thinking ?: '') + delta.thinking.toString()
						}
						break
					case 'content_block_stop':
						Integer index = event.index as Integer
						Map block = blocks.get(index)
						if (block != null) {
							StringBuilder buffer = jsonBuffers.remove(index)
							if (block.type == 'tool_use' && buffer != null && buffer.length() > 0) {
								try {
									block.input = slurper.parseText(buffer.toString())
								} catch (Exception ignored) {
									log.warn("Could not parse streamed tool_use input for block ${index}")
									block.input = [:]
								}
							} else if (block.type == 'tool_use' && block.input == null) {
								block.input = [:]
							}
						}
						break
					case 'message_delta':
						Map delta = event.delta instanceof Map ? event.delta as Map : [:]
						if (delta.stop_reason != null) {
							accumulated.stop_reason = delta.stop_reason
						}
						if (event.usage instanceof Map) {
							accumulated.usage.putAll(event.usage as Map)
						}
						break
					case 'error':
						Map error = event.error instanceof Map ? event.error as Map : [:]
						throw new RuntimeException("Anthropic stream error: ${error.type ?: 'unknown'} - ${error.message ?: ''}")
					default:
						break
				}
			}
		}

		blocks.keySet().sort().each { Integer index ->
			accumulated.content << blocks.get(index)
		}
		return accumulated
	}

	protected Map executeGet(String baseUrl, String path, String apiKey, String apiVersion, List<String> betas = null, Map opts = [:], Map<CharSequence, CharSequence> queryParams = null) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, apiVersion, ['Accept': 'application/json'], null, DEFAULT_READ_TIMEOUT, betas)
			if (queryParams) {
				requestOptions.queryParams = queryParams
			}
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'GET')
				return normalizeResponse(apiResponse)
			}
		} catch (Exception e) {
			log.error("Error executing GET ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	protected Map executePost(String baseUrl, String path, String apiKey, Map requestBody, String apiVersion, List<String> betas = null, Map opts = [:]) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, apiVersion, [
				'Content-Type': 'application/json',
				'Accept'      : 'application/json'
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT, betas)
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'POST')
				return normalizeResponse(apiResponse)
			}
		} catch (Exception e) {
			log.error("Error executing POST ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	protected Map normalizeResponse(ServiceResponse apiResponse) {
		if (apiResponse?.success) {
			Map responseData = apiResponse?.data instanceof Map ? apiResponse.data as Map : [:]
			if (!(apiResponse?.data instanceof Map) && apiResponse?.data != null) {
				responseData.data = apiResponse.data
			}
			return [success: true, data: responseData, headers: apiResponse?.headers]
		}
		return [success: false, msg: buildErrorMessage(apiResponse), headers: apiResponse?.headers]
	}

	protected <T> T withApiClient(Map opts = [:], Closure<T> work) {
		evictExpiredSessionClients()
		String clientScopeKey = opts?.get(CLIENT_SCOPE_KEY)?.toString()?.trim() ?: null
		if (clientScopeKey) {
			long now = System.currentTimeMillis()
			SessionClientHolder sessionClient = sessionClients.compute(clientScopeKey) { String key, SessionClientHolder existing ->
				if (existing && !isExpired(existing, now)) {
					existing.lastUsedAt = now
					return existing
				}
				if (existing?.apiClient) {
					existing.apiClient.shutdownClient()
				}
				return new SessionClientHolder(new HttpApiClient(true), now)
			}
			return work.call(sessionClient.apiClient)
		}
		HttpApiClient apiClient = new HttpApiClient()
		try {
			return work.call(apiClient)
		} finally {
			apiClient?.shutdownClient()
		}
	}

	protected void evictExpiredSessionClients() {
		long now = System.currentTimeMillis()
		sessionClients.each { String clientScopeKey, SessionClientHolder holder ->
			if (holder && isExpired(holder, now) && sessionClients.remove(clientScopeKey, holder)) {
				holder.apiClient?.shutdownClient()
			}
		}
	}

	protected boolean isExpired(SessionClientHolder holder, long now = System.currentTimeMillis()) {
		return holder == null || (now - holder.lastUsedAt) > SESSION_CLIENT_TTL_MS
	}

	/**
	 * Anthropic authenticates with x-api-key, so apiToken is deliberately left
	 * unset — setting it would make HttpApiClient send an Authorization: Bearer
	 * header, which api.anthropic.com rejects.
	 */
	protected HttpApiClient.RequestOptions buildRequestOptions(String apiKey, String apiVersion, Map<CharSequence, CharSequence> headers, Object body, Integer readTimeout, List<String> betas = null) {
		Map<CharSequence, CharSequence> allHeaders = [:]
		allHeaders.putAll(headers ?: [:])
		allHeaders.put(API_KEY_HEADER, apiKey)
		allHeaders.put(VERSION_HEADER, apiVersion ?: DEFAULT_API_VERSION)
		List<String> activeBetas = betas?.findAll { it } ?: []
		if (activeBetas) {
			allHeaders.put(BETA_HEADER, activeBetas.join(','))
		}

		HttpApiClient.RequestOptions options = new HttpApiClient.RequestOptions(
			headers          : allHeaders,
			ignoreSSL        : false,
			connectionTimeout: DEFAULT_CONNECTION_TIMEOUT,
			readTimeout      : readTimeout
		)
		if (body != null) {
			options.body = body
		}
		return options
	}

	protected String buildErrorMessage(ServiceResponse response) {
		if (response == null) {
			return 'Unknown error'
		}
		Map responseData = response.data instanceof Map ? response.data as Map : [:]
		// Anthropic errors: {"type":"error","error":{"type":"invalid_request_error","message":"..."}}
		String errorMessage = null
		if (responseData.error instanceof Map) {
			Map error = responseData.error as Map
			errorMessage = error.message?.toString() ?: error.type?.toString()
		}
		errorMessage = errorMessage ?: responseData.message?.toString() ?: response.content ?: response.error ?: response.msg
		String errorCode = response.errorCode ?: response.statusCode
		if (errorCode && errorMessage) {
			return "API returned ${errorCode}: ${errorMessage}"
		}
		return errorCode ? "API returned ${errorCode}" : (errorMessage ?: 'Unknown error')
	}

	protected String readErrorBody(CloseableHttpResponse response) {
		try {
			if (response?.entity) {
				return EntityUtils.toString(response.entity)
			}
		} catch (Exception ignored) {
		}
		return null
	}
}
