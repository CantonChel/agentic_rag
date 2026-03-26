package com.agenticrag.app.llm;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinimaxReasoningSupportTest {
	@Test
	void appliesReasoningSplitOnlyForMinimax() {
		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
			.model("MiniMax-M2.7")
			.addSystemMessage("system");
		MinimaxClientProperties props = new MinimaxClientProperties();
		props.setReasoningSplit(true);

		MinimaxReasoningSupport.applyReasoningSplit(builder, LlmProvider.MINIMAX, props);

		ChatCompletionCreateParams params = builder.build();
		Assertions.assertEquals(Boolean.TRUE, params._additionalBodyProperties().get("reasoning_split").convert(Boolean.class));
	}

	@Test
	void reasoningDetailsSnapshotIsReducedToIncrementalSuffix() {
		ChatCompletionChunk.Choice.Delta delta1 = ChatCompletionChunk.Choice.Delta.builder()
			.putAdditionalProperty("reasoning_details", JsonValue.from(List.of(Map.of("text", "先"))))
			.build();
		ChatCompletionChunk.Choice.Delta delta2 = ChatCompletionChunk.Choice.Delta.builder()
			.putAdditionalProperty("reasoning_details", JsonValue.from(List.of(Map.of("text", "先想"))))
			.build();

		StringBuilder buffer = new StringBuilder();
		MinimaxReasoningSupport.ExtractedReasoning first = MinimaxReasoningSupport.extractReasoning(delta1);
		MinimaxReasoningSupport.ExtractedReasoning second = MinimaxReasoningSupport.extractReasoning(delta2);

		Assertions.assertNotNull(first);
		Assertions.assertNotNull(second);
		Assertions.assertEquals("先", MinimaxReasoningSupport.mergeReasoning(buffer, first));
		Assertions.assertEquals("想", MinimaxReasoningSupport.mergeReasoning(buffer, second));
		Assertions.assertEquals("先想", buffer.toString());
		Assertions.assertEquals(MinimaxReasoningSupport.SOURCE_REASONING_DETAILS, second.source());
	}
}
