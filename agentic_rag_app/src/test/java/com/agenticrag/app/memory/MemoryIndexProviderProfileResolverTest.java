package com.agenticrag.app.memory;

import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.memory.index.MemoryIndexProviderProfile;
import com.agenticrag.app.memory.index.MemoryIndexProviderProfileResolver;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MemoryIndexProviderProfileResolverTest {
	@Test
	void fallsBackToSiliconFlowWhenOpenAiProviderHasNoKey() {
		RagEmbeddingProperties rag = new RagEmbeddingProperties();
		rag.setProvider("openai");

		OpenAiEmbeddingProperties openAi = new OpenAiEmbeddingProperties();
		openAi.setModel("text-embedding-3-small");

		SiliconFlowEmbeddingProperties silicon = new SiliconFlowEmbeddingProperties();
		silicon.setApiKey("sf-key");
		silicon.setModel("BAAI/bge-large-zh-v1.5");

		OpenAiClientProperties openAiClient = new OpenAiClientProperties();
		openAiClient.setApiKey("");

		MemoryIndexProviderProfileResolver resolver = new MemoryIndexProviderProfileResolver(rag, openAi, silicon, openAiClient);

		MemoryIndexProviderProfile profile = resolver.resolveCurrent();

		Assertions.assertEquals("siliconflow", profile.getProvider());
		Assertions.assertEquals("BAAI/bge-large-zh-v1.5", profile.getModel());
		Assertions.assertNotEquals("missing", profile.getProviderKeyFingerprint());
	}
}
