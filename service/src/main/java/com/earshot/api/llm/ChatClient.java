package com.earshot.api.llm;

import java.util.List;
import java.util.function.Consumer;

import com.earshot.api.llm.LlmClient.Message;

import tools.jackson.databind.JsonNode;

/** One AI provider: a local server, OpenAI or Anthropic. Failures throw {@link LlmClient.LlmUnavailable}. */
public interface ChatClient {

	/** "local", "openai" or "anthropic", for logs and messages. */
	String name();

	String chat(String model, List<Message> messages, boolean jsonMode);

	/** Streams tokens to {@code onToken}; returns the whole text. */
	String chatStream(String model, List<Message> messages, Consumer<String> onToken);

	float[][] embed(String model, List<String> texts);

	boolean available();

	int queueDepth();

	/** Pulls the first JSON object out of a reply that may wrap it in prose or code fences. */
	JsonNode parseJson(String reply);
}
