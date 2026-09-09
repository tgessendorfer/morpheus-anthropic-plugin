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
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Syncs the model catalog returned by GET /v1/models into Morpheus.
 * Models that disappear from the catalog are disabled rather than deleted so
 * existing Agents keep a resolvable reference.
 */
@Slf4j
class LlmModelsSync {

	protected final MorpheusContext morpheusContext
	protected final LlmIntegration llmIntegration
	protected final String providerCode
	protected final AnthropicApiService apiService

	LlmModelsSync(MorpheusContext morpheusContext, LlmIntegration llmIntegration, String providerCode, AnthropicApiService apiService) {
		this.morpheusContext = morpheusContext
		this.llmIntegration = llmIntegration
		this.providerCode = providerCode
		this.apiService = apiService ?: new AnthropicApiService()
	}

	Map execute(String baseUrl, String apiKey, String apiVersion, Map opts = [:], Closure<Collection<LlmModel>> modelBuilder) {
		Map result = apiService.listModels(baseUrl, apiKey, apiVersion, opts) ?: [success: false, msg: 'No response from the Anthropic models API']
		if (result.success) {
			Collection<LlmModel> freshModels = []
			if (modelBuilder) {
				Map apiResponse = result.data instanceof Map ? result.data as Map : [:]
				def builtModels = modelBuilder.call(apiResponse)
				if (builtModels instanceof Collection) {
					freshModels = builtModels as Collection<LlmModel>
				}
			}
			execute(freshModels)
		} else {
			log.warn("Failed to refresh models from Anthropic: ${result.msg}")
		}
		return result
	}

	void execute(Collection<LlmModel> freshModels) {
		if (!morpheusContext || !providerCode) {
			log.warn('Skipping Anthropic model sync: sync context is incomplete')
			return
		}
		if (!llmIntegration?.id) {
			log.warn('Skipping Anthropic model sync: LLM integration is not persisted')
			return
		}
		Collection<LlmModel> normalizedFreshModels = freshModels ?: []
		DataQuery query = new DataQuery().withFilter('providerCode', providerCode).withFilter('llmIntegration.id', llmIntegration.id)
		SyncTask<LlmModel, LlmModel, LlmModel> syncTask = new SyncTask<>(morpheusContext.llm.model.list(query), normalizedFreshModels)
		syncTask.addMatchFunction { LlmModel existingModel, LlmModel freshModel ->
			existingModel.code == freshModel.code
		}.onDelete { List<LlmModel> removeList ->
			disableMissingModels(removeList)
		}.onAdd { List<LlmModel> addList ->
			addMissingModels(addList)
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<LlmModel, LlmModel>> updateItems ->
			Observable.fromIterable(updateItems.collect { SyncTask.UpdateItemDto<LlmModel, LlmModel> updateItem ->
				new SyncTask.UpdateItem<LlmModel, LlmModel>(existingItem: updateItem.existingItem, masterItem: updateItem.masterItem)
			})
		}.onUpdate { List<SyncTask.UpdateItem<LlmModel, LlmModel>> updateList ->
			updateMatchedModels(updateList)
		}.start()
	}

	protected void disableMissingModels(List<LlmModel> removeList) {
		List<LlmModel> saveList = []
		removeList?.each { LlmModel model ->
			if (model?.enabled) {
				log.debug("Disabling removed Anthropic model: ${model.code}")
				model.enabled = false
				saveList << model
			}
		}
		if (saveList) {
			morpheusContext.llm.model.bulkSave(saveList).blockingGet()
		}
	}

	protected void addMissingModels(List<LlmModel> addList) {
		if (!addList) {
			return
		}
		addList.each { LlmModel model ->
			model.llmIntegration = llmIntegration
			model.enabled = model.enabled != false
			log.debug("Adding new Anthropic model: ${model.code}")
		}
		morpheusContext.llm.model.bulkCreate(addList).blockingGet()
	}

	protected void updateMatchedModels(List<SyncTask.UpdateItem<LlmModel, LlmModel>> updateList) {
		List<LlmModel> saveList = []
		updateList?.each { SyncTask.UpdateItem<LlmModel, LlmModel> updateItem ->
			LlmModel existingModel = updateItem.existingItem
			LlmModel freshModel = updateItem.masterItem
			boolean changed = false

			if (existingModel.llmIntegration?.id != llmIntegration.id) {
				existingModel.llmIntegration = llmIntegration
				changed = true
			}
			if (!existingModel.enabled) {
				existingModel.enabled = true
				changed = true
			}
			if (existingModel.contextWindow != freshModel.contextWindow) {
				existingModel.contextWindow = freshModel.contextWindow
				changed = true
			}
			if (existingModel.name != freshModel.name) {
				existingModel.name = freshModel.name
				changed = true
			}
			if (existingModel.maxOutputTokens != freshModel.maxOutputTokens) {
				existingModel.maxOutputTokens = freshModel.maxOutputTokens
				changed = true
			}
			Map existingMetadata = existingModel.metadata ?: [:]
			Map freshMetadata = freshModel.metadata ?: [:]
			if (existingMetadata != freshMetadata) {
				existingModel.metadata = freshMetadata
				changed = true
			}
			if (changed) {
				saveList << existingModel
			}
		}
		if (saveList) {
			morpheusContext.llm.model.bulkSave(saveList).blockingGet()
		}
	}
}
