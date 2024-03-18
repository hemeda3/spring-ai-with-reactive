/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.anthropic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.metadata.AnthropicChatResponseMetadata;
import org.springframework.ai.anthropic.metadata.support.AnthropicResponseHeaderExtractor;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.AbstractFunctionCallSupport;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletion;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletion.Choice;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionFinishReason;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionMessage.Role;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * {@link ChatClient} and {@link StreamingChatClient} implementation for {@literal OpenAI}
 * backed by {@link AnthropicApi}.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Ueibin Kim
 * @author John Blum
 * @author Josh Long
 * @author Jemin Huh
 * @see ChatClient
 * @see StreamingChatClient
 * @see AnthropicApi
 */
public class AnthropicChatClient extends
		AbstractFunctionCallSupport<ChatCompletionMessage, AnthropicApi.ChatCompletionRequest, ResponseEntity<ChatCompletion>>
		implements ChatClient, StreamingChatClient {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicChatClient.class);

	/**
	 * The default options used for the chat completion requests.
	 */
	private AnthropicChatOptions defaultOptions;

	/**
	 * The retry template used to retry the OpenAI API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the OpenAI API.
	 */
	private final AnthropicApi anthropicApi;

	/**
	 * Creates an instance of the AnthropicChatClient.
	 * @param anthropicApi The AnthropicApi instance to be used for interacting with the OpenAI
	 * Chat API.
	 * @throws IllegalArgumentException if anthropicApi is null
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi) {
		this(anthropicApi,
				AnthropicChatOptions.builder().withModel(AnthropicApi.DEFAULT_CHAT_MODEL).withTemperature(0.7f).build());
	}

	/**
	 * Initializes an instance of the AnthropicChatClient.
	 * @param anthropicApi The AnthropicApi instance to be used for interacting with the OpenAI
	 * Chat API.
	 * @param options The AnthropicChatOptions to configure the chat client.
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi, AnthropicChatOptions options) {
		this(anthropicApi, options, null, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Initializes a new instance of the AnthropicChatClient.
	 * @param anthropicApi The AnthropicApi instance to be used for interacting with the OpenAI
	 * Chat API.
	 * @param options The AnthropicChatOptions to configure the chat client.
	 * @param functionCallbackContext The function callback context.
	 * @param retryTemplate The retry template.
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi, AnthropicChatOptions options,
							   FunctionCallbackContext functionCallbackContext, RetryTemplate retryTemplate) {
		super(functionCallbackContext);
		Assert.notNull(anthropicApi, "AnthropicApi must not be null");
		Assert.notNull(options, "Options must not be null");
		Assert.notNull(retryTemplate, "RetryTemplate must not be null");
		this.anthropicApi = anthropicApi;
		this.defaultOptions = options;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public ChatResponse call(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, false);

		return this.retryTemplate.execute(ctx -> {

			ResponseEntity<ChatCompletion> completionEntity = this.callWithFunctionSupport(request);

			var chatCompletion = completionEntity.getBody();
			if (chatCompletion == null) {
				logger.warn("No chat completion returned for prompt: {}", prompt);
				return new ChatResponse(List.of());
			}

			RateLimit rateLimits = AnthropicResponseHeaderExtractor.extractAiResponseHeaders(completionEntity);

			List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
				return new Generation(choice.message().content(), toMap(chatCompletion.id(), choice))
					.withGenerationMetadata(ChatGenerationMetadata.from(choice.finishReason().name(), null));
			}).toList();

			return new ChatResponse(generations,
					AnthropicChatResponseMetadata.from(completionEntity.getBody()).withRateLimit(rateLimits));
		});
	}

	private Map<String, Object> toMap(String id, ChatCompletion.Choice choice) {
		Map<String, Object> map = new HashMap<>();

		var message = choice.message();
		if (message.role() != null) {
			map.put("role", message.role().name());
		}
		if (choice.finishReason() != null) {
			map.put("finishReason", choice.finishReason().name());
		}
		map.put("id", id);
		return map;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, true);

		return this.retryTemplate.execute(ctx -> {

			Flux<AnthropicApi.ChatCompletionChunk> completionChunks = this.anthropicApi.chatCompletionStream(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			// Convert the ChatCompletionChunk into a ChatCompletion to be able to reuse
			// the function call handling logic.
			return completionChunks.map(chunk -> chunkToChatCompletion(chunk)).map(chatCompletion -> {
				try {
					chatCompletion = handleFunctionCallOrReturn(request, ResponseEntity.of(Optional.of(chatCompletion)))
						.getBody();

					@SuppressWarnings("null")
					String id = chatCompletion.id();

					List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
						if (choice.message().role() != null) {
							roleMap.putIfAbsent(id, choice.message().role().name());
						}
						String finish = (choice.finishReason() != null ? choice.finishReason().name() : "");
						var generation = new Generation(choice.message().content(),
								Map.of("id", id, "role", roleMap.get(id), "finishReason", finish));
						if (choice.finishReason() != null) {
							generation = generation.withGenerationMetadata(
									ChatGenerationMetadata.from(choice.finishReason().name(), null));
						}
						return generation;
					}).toList();

					return new ChatResponse(generations);
				}
				catch (Exception e) {
					logger.error("Error processing chat completion", e);
					return new ChatResponse(List.of());
				}

			});
		});
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private AnthropicApi.ChatCompletion chunkToChatCompletion(AnthropicApi.ChatCompletionChunk chunk) {
		List<Choice> choices = chunk.choices()
			.stream()
			.map(cc -> new Choice(cc.finishReason(), cc.index(), cc.delta(), cc.logprobs()))
			.toList();

		return new AnthropicApi.ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(),
				chunk.systemFingerprint(), "chat.completion", null);
	}

	/**
	 * Accessible for testing.
	 */
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		Set<String> functionsForThisRequest = new HashSet<>();

		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions()
			.stream()
			.map(m -> new ChatCompletionMessage(m.getContent(),
					ChatCompletionMessage.Role.valueOf(m.getMessageType().name())))
			.toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ChatOptions runtimeOptions) {
				AnthropicChatOptions updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(runtimeOptions,
						ChatOptions.class, AnthropicChatOptions.class);

				Set<String> promptEnabledFunctions = this.handleFunctionCallbackConfigurations(updatedRuntimeOptions,
						IS_RUNTIME_CALL);
				functionsForThisRequest.addAll(promptEnabledFunctions);

				request = ModelOptionsUtils.merge(updatedRuntimeOptions, request, ChatCompletionRequest.class);
			}
			else {
				throw new IllegalArgumentException("Prompt options are not of type ChatOptions: "
						+ prompt.getOptions().getClass().getSimpleName());
			}
		}

		if (this.defaultOptions != null) {

			Set<String> defaultEnabledFunctions = this.handleFunctionCallbackConfigurations(this.defaultOptions,
					!IS_RUNTIME_CALL);

			functionsForThisRequest.addAll(defaultEnabledFunctions);

			request = ModelOptionsUtils.merge(request, this.defaultOptions, ChatCompletionRequest.class);
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {

			request = ModelOptionsUtils.merge(
					AnthropicChatOptions.builder().withTools(this.getFunctionTools(functionsForThisRequest)).build(),
					request, ChatCompletionRequest.class);
		}

		return request;
	}

	private List<AnthropicApi.FunctionTool> getFunctionTools(Set<String> functionNames) {
		return this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			var function = new AnthropicApi.FunctionTool.Function(functionCallback.getDescription(),
					functionCallback.getName(), functionCallback.getInputTypeSchema());
			return new AnthropicApi.FunctionTool(function);
		}).toList();
	}

	@Override
	protected ChatCompletionRequest doCreateToolResponseRequest(ChatCompletionRequest previousRequest,
			ChatCompletionMessage responseMessage, List<ChatCompletionMessage> conversationHistory) {

		// Every tool-call item requires a separate function call and a response (TOOL)
		// message.
		for (ToolCall toolCall : responseMessage.toolCalls()) {

			var functionName = toolCall.function().name();
			String functionArguments = toolCall.function().arguments();

			if (!this.functionCallbackRegister.containsKey(functionName)) {
				throw new IllegalStateException("No function callback found for function name: " + functionName);
			}

			String functionResponse = this.functionCallbackRegister.get(functionName).call(functionArguments);

			// Add the function response to the conversation.
			conversationHistory
				.add(new ChatCompletionMessage(functionResponse, Role.TOOL, functionName, toolCall.id(), null));
		}

		// Recursively call chatCompletionWithTools until the model doesn't call a
		// functions anymore.
		ChatCompletionRequest newRequest = new ChatCompletionRequest(conversationHistory, false);
		newRequest = ModelOptionsUtils.merge(newRequest, previousRequest, ChatCompletionRequest.class);

		return newRequest;
	}

	@Override
	protected List<ChatCompletionMessage> doGetUserMessages(ChatCompletionRequest request) {
		return request.messages();
	}

	@Override
	protected ChatCompletionMessage doGetToolResponseMessage(ResponseEntity<ChatCompletion> chatCompletion) {
		return chatCompletion.getBody().choices().iterator().next().message();
	}

	@Override
	protected ResponseEntity<ChatCompletion> doChatCompletion(ChatCompletionRequest request) {
		return this.anthropicApi.chatCompletionEntity(request);
	}

	@Override
	protected boolean isToolFunctionCall(ResponseEntity<ChatCompletion> chatCompletion) {
		var body = chatCompletion.getBody();
		if (body == null) {
			return false;
		}

		var choices = body.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return false;
		}

		var choice = choices.get(0);
		return !CollectionUtils.isEmpty(choice.message().toolCalls())
				&& choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS;
	}

}