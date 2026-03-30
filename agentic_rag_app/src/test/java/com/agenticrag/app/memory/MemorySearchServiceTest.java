package com.agenticrag.app.memory;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemorySearchServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void searchOnlyUsesGlobalAndCurrentUserMemoryFiles() throws Exception {
		Files.writeString(tempDir.resolve("MEMORY.md"), "global handbook for release deadline", StandardCharsets.UTF_8);
		Files.createDirectories(tempDir.resolve("memory/users/u1/daily"));
		Files.createDirectories(tempDir.resolve("memory/users/u1/sessions"));
		Files.createDirectories(tempDir.resolve("memory/users/u2/daily"));
		Files.writeString(tempDir.resolve("memory/users/u1/daily/2026-03-29.md"), "- banana project for u1", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u1/sessions/2026-03-29-note.md"), "- vendor decision for u1", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u2/daily/2026-03-29.md"), "- secret project for u2", StandardCharsets.UTF_8);

		MemoryRecallService service = newService(tempDir, new CountingEmbeddingModel());
		List<MemorySearchHit> result = service.search("u1", "banana", 5);

		Assertions.assertFalse(result.isEmpty());
		Assertions.assertTrue(result.stream().anyMatch(hit -> hit.getPath().contains("memory/users/u1/")));
		Assertions.assertFalse(result.stream().anyMatch(hit -> hit.getPath().contains("memory/users/u2/")));
	}

	@Test
	void fileChangesRebuildChunksButReuseEmbeddingCacheForUnchangedChunks() throws Exception {
		Path file = tempDir.resolve("memory/users/u1/daily/2026-03-29.md");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "alpha block\nbeta block\n", StandardCharsets.UTF_8);

		CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel();
		MemoryRecallService service = newService(tempDir, embeddingModel, 12);
		service.search("u1", "alpha", 5);
		int afterFirstSearch = embeddingModel.getEmbeddedTextCount();

		Files.writeString(file, "alpha block\nbeta block\ngamma block\n", StandardCharsets.UTF_8);
		service.search("u1", "gamma", 5);
		int delta = embeddingModel.getEmbeddedTextCount() - afterFirstSearch;

		Assertions.assertEquals(2, delta);
		Assertions.assertTrue(Files.exists(tempDir.resolve("memory/.cache/embeddings/openai/test-embedding")));
	}

	private MemoryRecallService newService(Path workspaceRoot, EmbeddingModel embeddingModel) {
		return newService(workspaceRoot, embeddingModel, 800);
	}

	private MemoryRecallService newService(Path workspaceRoot, EmbeddingModel embeddingModel, int maxChunkChars) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		props.setEmbeddingCacheDir("memory/.cache/embeddings");
		props.setMaxChunkChars(maxChunkChars);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		RagEmbeddingProperties ragProps = new RagEmbeddingProperties();
		ragProps.setProvider("openai");
		OpenAiEmbeddingProperties openAiProps = new OpenAiEmbeddingProperties();
		openAiProps.setModel("test-embedding");
		SiliconFlowEmbeddingProperties siliconProps = new SiliconFlowEmbeddingProperties();
		siliconProps.setModel("silicon-test");
		return new MemoryRecallService(
			props,
			embeddingModel,
			fileService,
			blockParser,
			ragProps,
			openAiProps,
			siliconProps,
			new ObjectMapper()
		);
	}

	private static class CountingEmbeddingModel implements EmbeddingModel {
		private int embeddedTextCount = 0;

		@Override
		public List<List<Double>> embedTexts(List<String> texts) {
			if (texts == null) {
				return Collections.emptyList();
			}
			embeddedTextCount += texts.size();
			List<List<Double>> out = new ArrayList<>();
			for (String text : texts) {
				String lower = text == null ? "" : text.toLowerCase();
				double alpha = lower.contains("alpha") ? 1.0 : 0.0;
				double beta = lower.contains("beta") ? 1.0 : 0.0;
				double gamma = lower.contains("gamma") ? 1.0 : 0.0;
				double banana = lower.contains("banana") ? 1.0 : 0.0;
				out.add(List.of(alpha, beta, gamma + banana));
			}
			return out;
		}

		int getEmbeddedTextCount() {
			return embeddedTextCount;
		}
	}
}
