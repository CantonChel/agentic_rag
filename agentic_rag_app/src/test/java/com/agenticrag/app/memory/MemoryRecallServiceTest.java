package com.agenticrag.app.memory;

import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MemoryRecallServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void searchShouldNotLeakOtherUsersMemoryFiles() throws Exception {
		Files.writeString(tempDir.resolve("MEMORY.md"), "global handbook", StandardCharsets.UTF_8);
		Files.createDirectories(tempDir.resolve("memory/users/u1"));
		Files.createDirectories(tempDir.resolve("memory/users/u2"));
		Files.writeString(tempDir.resolve("memory/users/u1/u1.md"), "banana project for u1", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u2/u2.md"), "secret project for u2", StandardCharsets.UTF_8);

		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setIncludeTranscripts(false);
		props.setTopK(5);

		PersistentMessageStore store = Mockito.mock(PersistentMessageStore.class);
		Mockito.when(store.listSessionIds()).thenReturn(Collections.emptyList());

		MemoryRecallService service = new MemoryRecallService(props, new FakeEmbeddingModel(), store);
		List<TextChunk> result = service.search("u1", "banana", 5);

		Assertions.assertFalse(result.isEmpty());
		Assertions.assertTrue(result.stream().anyMatch(c -> sourceOf(c).contains("memory/users/u1/")));
		Assertions.assertFalse(result.stream().anyMatch(c -> sourceOf(c).contains("memory/users/u2/")));
	}

	@Test
	void searchShouldOnlyUseCurrentUserTranscripts() {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setIncludeTranscripts(true);
		props.setTopK(5);

		PersistentMessageStore store = Mockito.mock(PersistentMessageStore.class);
		Mockito.when(store.listSessionIds()).thenReturn(Arrays.asList("u1::s1", "u2::s2"));
		Mockito.when(store.list("u1::s1")).thenReturn(Arrays.asList(msg("USER", "deadline Friday for release")));
		Mockito.when(store.list("u2::s2")).thenReturn(Arrays.asList(msg("USER", "salary review only")));

		MemoryRecallService service = new MemoryRecallService(props, new FakeEmbeddingModel(), store);
		List<TextChunk> result = service.search("u1", "deadline", 5);

		Assertions.assertTrue(result.stream().anyMatch(c -> sourceOf(c).contains("session:s1")));
		Assertions.assertFalse(result.stream().anyMatch(c -> sourceOf(c).contains("session:s2")));
	}

	@Test
	void defaultShouldNotSearchTranscriptsUnlessEnabled() {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		// keep default includeTranscripts=false

		PersistentMessageStore store = Mockito.mock(PersistentMessageStore.class);
		Mockito.when(store.listSessionIds()).thenReturn(Arrays.asList("u1::s1"));
		Mockito.when(store.list("u1::s1")).thenReturn(Arrays.asList(msg("USER", "deadline Friday for release")));

		MemoryRecallService service = new MemoryRecallService(props, new FakeEmbeddingModel(), store);
		List<TextChunk> result = service.search("u1", "deadline", 5);

		Assertions.assertTrue(result.isEmpty());
	}

	private String sourceOf(TextChunk chunk) {
		Object source = chunk.getMetadata() != null ? chunk.getMetadata().get("source") : null;
		return source != null ? source.toString() : "";
	}

	private StoredMessageEntity msg(String type, String content) {
		StoredMessageEntity e = new StoredMessageEntity();
		e.setType(type);
		e.setContent(content);
		return e;
	}

	private static class FakeEmbeddingModel implements EmbeddingModel {
		@Override
		public List<List<Double>> embedTexts(List<String> texts) {
			List<List<Double>> out = new ArrayList<>();
			if (texts == null) {
				return out;
			}
			for (String text : texts) {
				String t = text == null ? "" : text.toLowerCase();
				double deadline = containsCount(t, "deadline");
				double banana = containsCount(t, "banana");
				double salary = containsCount(t, "salary");
				out.add(Arrays.asList(deadline, banana, salary + 0.0001));
			}
			return out;
		}

		private static double containsCount(String text, String needle) {
			if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
				return 0.0;
			}
			int from = 0;
			int count = 0;
			while (true) {
				int idx = text.indexOf(needle, from);
				if (idx < 0) {
					break;
				}
				count++;
				from = idx + needle.length();
			}
			return (double) count;
		}
	}
}
