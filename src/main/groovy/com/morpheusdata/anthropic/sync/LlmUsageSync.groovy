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
package com.morpheusdata.anthropic.sync

import com.morpheusdata.anthropic.AnthropicApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.llm.LlmIntegration
import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * Populates the token/request usage fields on LlmIntegration from the Anthropic
 * rate limit response headers, so the Morpheus UI can display remaining budget.
 *
 * Anthropic reports separate buckets for requests, input tokens and output
 * tokens. Morpheus only models one token bucket, so the input token bucket is
 * used - it is the one that MCP-heavy agent traffic actually exhausts.
 */
@Slf4j
class LlmUsageSync {

	protected final LlmIntegration llmIntegration
	protected final MorpheusContext morpheusContext

	static final String REQUESTS_LIMIT = 'anthropic-ratelimit-requests-limit'
	static final String REQUESTS_REMAINING = 'anthropic-ratelimit-requests-remaining'
	static final String REQUESTS_RESET = 'anthropic-ratelimit-requests-reset'
	static final String INPUT_TOKENS_LIMIT = 'anthropic-ratelimit-input-tokens-limit'
	static final String INPUT_TOKENS_REMAINING = 'anthropic-ratelimit-input-tokens-remaining'
	static final String INPUT_TOKENS_RESET = 'anthropic-ratelimit-input-tokens-reset'
	static final String TOKENS_LIMIT = 'anthropic-ratelimit-tokens-limit'
	static final String TOKENS_REMAINING = 'anthropic-ratelimit-tokens-remaining'
	static final String TOKENS_RESET = 'anthropic-ratelimit-tokens-reset'

	static final List<String> RATE_LIMIT_HEADERS = [
		REQUESTS_LIMIT, REQUESTS_REMAINING, REQUESTS_RESET,
		INPUT_TOKENS_LIMIT, INPUT_TOKENS_REMAINING, INPUT_TOKENS_RESET,
		TOKENS_LIMIT, TOKENS_REMAINING, TOKENS_RESET
	]

	LlmUsageSync(LlmIntegration llmIntegration, MorpheusContext morpheusContext) {
		this.llmIntegration = llmIntegration
		this.morpheusContext = morpheusContext
	}

	void execute(AnthropicApiService apiService, String baseUrl, String apiKey, String apiVersion, String model, Map opts = [:]) {
		if (!llmIntegration || !apiService) {
			return
		}
		Map usageResult = apiService.fetchUsageHeaders(baseUrl, apiKey, apiVersion, model, opts) ?: [success: false, msg: 'No usage response from the Anthropic API']
		if (usageResult.success != true) {
			log.warn("Unable to refresh Anthropic usage metrics: ${usageResult.msg ?: 'unknown error'}")
		}
		Map usageHeaders = extractRateLimitHeaders(usageResult?.headers instanceof Map ? usageResult.headers as Map : [:])
		if (!usageHeaders) {
			log.debug('No Anthropic rate limit headers present in the usage probe response')
			return
		}
		if (applyHeadersUsageMetrics(usageHeaders)) {
			morpheusContext?.llm?.integration?.save(llmIntegration)
		}
	}

	boolean applyHeadersUsageMetrics(Map headers) {
		if (!llmIntegration || !headers) {
			return false
		}
		boolean saveRequired = false

		Long tokenLimit = parseLongValue(headers[INPUT_TOKENS_LIMIT] ?: headers[TOKENS_LIMIT])
		Long tokenRemaining = parseLongValue(headers[INPUT_TOKENS_REMAINING] ?: headers[TOKENS_REMAINING])
		String tokenReset = parseResetAt(headers[INPUT_TOKENS_RESET] ?: headers[TOKENS_RESET])
		if (tokenLimit != null || tokenRemaining != null) {
			saveRequired = assignIfChanged('tokenUsageLimit', tokenLimit) || saveRequired
			saveRequired = assignIfChanged('tokenUsageRemaining', tokenRemaining) || saveRequired
			if (tokenLimit != null && tokenRemaining != null) {
				saveRequired = assignIfChanged('tokenUsageUsed', Math.max(tokenLimit - tokenRemaining, 0L)) || saveRequired
			}
			saveRequired = assignIfChanged('tokenUsagePeriod', 'minute') || saveRequired
			saveRequired = assignIfChanged('tokenUsageResetAt', tokenReset) || saveRequired
		}

		Long requestLimit = parseLongValue(headers[REQUESTS_LIMIT])
		Long requestRemaining = parseLongValue(headers[REQUESTS_REMAINING])
		String requestReset = parseResetAt(headers[REQUESTS_RESET])
		if (requestLimit != null || requestRemaining != null) {
			saveRequired = assignIfChanged('requestUsageLimit', requestLimit) || saveRequired
			saveRequired = assignIfChanged('requestUsageRemaining', requestRemaining) || saveRequired
			if (requestLimit != null && requestRemaining != null) {
				saveRequired = assignIfChanged('requestUsageUsed', Math.max(requestLimit - requestRemaining, 0L)) || saveRequired
			}
			saveRequired = assignIfChanged('requestUsagePeriod', 'minute') || saveRequired
			saveRequired = assignIfChanged('requestUsageResetAt', requestReset) || saveRequired
		}

		return saveRequired
	}

	protected Map<String, String> extractRateLimitHeaders(Map responseHeaders) {
		Map<String, String> headers = [:]
		if (!(responseHeaders instanceof Map) || responseHeaders.isEmpty()) {
			return headers
		}
		RATE_LIMIT_HEADERS.each { String headerName ->
			Map.Entry matchedHeader = responseHeaders.entrySet().find { entry ->
				entry?.key?.toString()?.equalsIgnoreCase(headerName) && entry?.value != null && entry.value.toString() != ''
			}
			if (matchedHeader) {
				headers[headerName] = matchedHeader.value.toString()
			}
		}
		return headers
	}

	protected Long parseLongValue(def value) {
		if (value == null || value == '') {
			return null
		}
		if (value instanceof Number) {
			return ((Number) value).longValue()
		}
		try {
			return value.toString().trim().toLong()
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Anthropic sends RFC3339 timestamps (2026-09-08T12:34:56Z). Epoch values are
	 * still tolerated in case a gateway rewrites the header.
	 */
	protected String parseResetAt(def value) {
		if (value == null || value.toString().trim().isEmpty()) {
			return null
		}
		String raw = value.toString().trim()
		Long epochValue = parseLongValue(raw)
		if (epochValue == null) {
			try {
				return Instant.parse(raw).toString()
			} catch (Exception ignored) {
				return raw
			}
		}
		try {
			return epochValue > 100000000000L ? Instant.ofEpochMilli(epochValue).toString() : Instant.ofEpochSecond(epochValue).toString()
		} catch (Exception ignored) {
			return raw
		}
	}

	protected boolean assignIfChanged(String propertyName, Object value) {
		if (llmIntegration?."${propertyName}" != value) {
			llmIntegration?."${propertyName}" = value
			return true
		}
		return false
	}
}
