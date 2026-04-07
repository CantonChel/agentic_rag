package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildRepository;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryRepository;
import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.trace.TraceIdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"llm.openai.model=gpt-test",
		"llm.minimax.model=minimax-test"
	}
)
@AutoConfigureWebTestClient
class BenchmarkExecutionLedgerIntegrationTest {
	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private BenchmarkBuildImportService benchmarkBuildImportService;

	@Autowired
	private BenchmarkBuildRepository benchmarkBuildRepository;

	@Autowired
	private BenchmarkTurnExecutionSummaryRepository benchmarkTurnExecutionSummaryRepository;

	@Autowired
	private BenchmarkRetrievalTraceRepository benchmarkRetrievalTraceRepository;

	@Autowired
	private KnowledgeBaseRepository knowledgeBaseRepository;

	@Autowired
	private KnowledgeRepository knowledgeRepository;

	@Autowired
	private ChunkRepository chunkRepository;

	@Autowired
	private EmbeddingRepository embeddingRepository;

	@MockBean(name = "openAiClient", answer = Answers.RETURNS_DEEP_STUBS)
	private OpenAIClient openAiClient;

	@MockBean(name = "minimaxClient", answer = Answers.RETURNS_DEEP_STUBS)
	private OpenAIClient minimaxClient;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
		benchmarkTurnExecutionSummaryRepository.deleteAll();
		benchmarkRetrievalTraceRepository.deleteAll();
		benchmarkBuildRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();

		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> texts = invocation.getArgument(0, List.class);
				return Arrays.asList(
					Arrays.asList(0.1d, 0.2d),
					Arrays.asList(0.3d, 0.4d),
					Arrays.asList(0.5d, 0.6d)
				).subList(0, texts.size());
			});
	}

	@Test
	void agentStreamCanBeReadBackFromTurnSummaryAndRetrievalTraceApis() throws Exception {
		Path packageDir = createPackageDir();
		BenchmarkBuildEntity build = benchmarkBuildImportService.importPackage(packageDir);

		StreamResponse<ChatCompletionChunk> first = new FakeStreamResponse(StreamResponseTestData.toolCallChunk());
		StreamResponse<ChatCompletionChunk> second = new FakeStreamResponse(StreamResponseTestData.textChunk("Benchmark answer from build"));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(first, second);

		FluxExchangeResult<String> result = webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/agent/openai/stream")
				.queryParam("userId", "bench-user")
				.queryParam("sessionId", "bench-session")
				.queryParam("buildId", build.getBuildId())
				.queryParam("kbScope", "BENCHMARK_BUILD")
				.queryParam("evalMode", "SINGLE_TURN")
				.queryParam("memoryEnabled", "false")
				.queryParam("prompt", "What is the benchmark evidence?")
				.build())
			.accept(MediaType.TEXT_EVENT_STREAM)
			.header(TraceIdUtil.HEADER_NAME, "trace-e2e")
			.exchange()
			.expectStatus().isOk()
			.returnResult(String.class);

		List<String> sseChunks = result.getResponseBody()
			.collectList()
			.block(Duration.ofSeconds(10));
		Assertions.assertNotNull(sseChunks);

		List<JsonNode> events = parseSseEvents(String.join("\n", sseChunks));
		JsonNode turnStart = events.stream()
			.filter(event -> "turn_start".equals(event.path("type").asText()))
			.findFirst()
			.orElseThrow();

		String turnId = turnStart.path("turnId").asText();
		Assertions.assertNotNull(turnId);

		byte[] summaryBody = webTestClient.get()
			.uri("/api/benchmark/turn-summaries/{turnId}", turnId)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.returnResult()
			.getResponseBodyContent();
		Assertions.assertNotNull(summaryBody);
		JsonNode summaryJson = objectMapper.readTree(summaryBody);
		Assertions.assertEquals(build.getBuildId(), summaryJson.path("buildId").asText());
		Assertions.assertEquals(build.getKnowledgeBaseId(), summaryJson.path("knowledgeBaseId").asText());
		Assertions.assertEquals("Benchmark answer from build", summaryJson.path("finalAnswer").asText());
		Assertions.assertEquals("stop", summaryJson.path("finishReason").asText());
		Assertions.assertEquals("single_turn", summaryJson.path("evalMode").asText());
		Assertions.assertEquals("benchmark_build", summaryJson.path("kbScope").asText());
		Assertions.assertFalse(summaryJson.path("memoryEnabled").asBoolean());
		Assertions.assertEquals("search_knowledge_base", summaryJson.path("toolCalls").get(0).path("toolName").asText());
		Assertions.assertTrue(summaryJson.path("retrievalTraceIds").isArray());
		Assertions.assertEquals("trace-e2e", summaryJson.path("retrievalTraceIds").get(0).asText());

		byte[] retrievalBody = webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/retrieval-traces")
				.queryParam("traceId", "trace-e2e")
				.build())
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.returnResult()
			.getResponseBodyContent();
		Assertions.assertNotNull(retrievalBody);
		JsonNode retrievalJson = objectMapper.readTree(retrievalBody);
		Assertions.assertTrue(retrievalJson.isArray());
		Assertions.assertTrue(retrievalJson.size() > 0);
		Assertions.assertTrue(streamContainsChunkForBuild(retrievalJson, build.getBuildId()));
	}

	private boolean streamContainsChunkForBuild(JsonNode retrievalJson, String buildId) {
		for (JsonNode node : retrievalJson) {
			if ("chunk".equals(node.path("recordType").asText())
				&& "context_output".equals(node.path("stage").asText())
				&& buildId.equals(node.path("buildId").asText())) {
				return true;
			}
		}
		return false;
	}

	private List<JsonNode> parseSseEvents(String rawBody) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		if (rawBody == null || rawBody.trim().isEmpty()) {
			return out;
		}
		for (String block : rawBody.split("\\n\\n")) {
			if (block == null || block.trim().isEmpty()) {
				continue;
			}
			String trimmedBlock = block.trim();
			if (trimmedBlock.startsWith("{") && trimmedBlock.endsWith("}")) {
				out.add(objectMapper.readTree(trimmedBlock));
				continue;
			}
			for (String line : block.split("\\n")) {
				String trimmedLine = line == null ? "" : line.trim();
				if (trimmedLine.isEmpty()) {
					continue;
				}
				if (trimmedLine.startsWith("{") && trimmedLine.endsWith("}")) {
					out.add(objectMapper.readTree(trimmedLine));
					continue;
				}
				if (!trimmedLine.startsWith("data:")) {
					continue;
				}
				String json = trimmedLine.substring("data:".length()).trim();
				if (json.isEmpty() || "[DONE]".equals(json)) {
					continue;
				}
				out.add(objectMapper.readTree(json));
			}
		}
		return out;
	}

	private Path createPackageDir() throws Exception {
		Path dir = Files.createTempDirectory("benchmark-stage5-e2e");
		Files.writeString(
			dir.resolve("source_manifest.json"),
			"{\n"
				+ "  \"source_set_id\": \"api_docs-source-set\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"source_root\": \"/tmp/source-set\",\n"
				+ "  \"file_count\": 1,\n"
				+ "  \"files\": [\n"
				+ "    {\"path\": \"docs/guide/a.md\", \"size_bytes\": 64, \"sha256\": \"sha-a\"}\n"
				+ "  ],\n"
				+ "  \"created_at\": \"2026-03-28T00:00:00Z\"\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("normalized_documents.jsonl"),
			"{\"doc_path\":\"docs/guide/a.md\",\"title\":\"Guide A\",\"normalized_text\":\"# Intro\\nBenchmark evidence body\\n\\n## Details\\nMore benchmark detail\",\"metadata\":{\"source_name\":\"a.md\"},\"blocks\":[{\"block_id\":\"block-1\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# Intro\\nBenchmark evidence body\",\"start_line\":1,\"end_line\":2},{\"block_id\":\"block-2\",\"section_key\":\"details\",\"section_title\":\"Details\",\"block_type\":\"section\",\"heading_level\":2,\"content\":\"## Details\\nMore benchmark detail\",\"start_line\":4,\"end_line\":5}]}\n"
		);
		Files.writeString(
			dir.resolve("authoring_blocks.jsonl"),
			"{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# Intro\\nBenchmark evidence body\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"start_line\":1,\"end_line\":2}\n"
				+ "{\"block_id\":\"block-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"details\",\"section_title\":\"Details\",\"block_type\":\"section\",\"heading_level\":2,\"text\":\"## Details\\nMore benchmark detail\",\"anchor\":\"docs/guide/a.md#details\",\"source_hash\":\"hash-2\",\"start_line\":4,\"end_line\":5}\n"
		);
		Files.writeString(
			dir.resolve("block_links.jsonl"),
			"{\"from_block_id\":\"block-1\",\"to_block_id\":\"block-2\",\"link_type\":\"prev_next\"}\n"
		);
		Files.writeString(
			dir.resolve("samples.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is the benchmark evidence?\",\"ground_truth\":\"Benchmark evidence body\",\"ground_truth_contexts\":[\"# Intro\\nBenchmark evidence body\"],\"gold_block_refs\":[{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"stage5_v1\"}\n"
		);
		Files.writeString(
			dir.resolve("sample_generation_trace.jsonl"),
			"{\"sample_id\":\"sample-1\",\"generation_method\":\"rule_based\",\"input_block_ids\":[\"block-1\"],\"generator_version\":\"gold_stage1_v1\",\"model_or_rule_name\":\"BenchmarkAuthoringStrategy\",\"validation_status\":\"generated\"}\n"
		);
		Files.writeString(
			dir.resolve("gold_package_manifest.json"),
			"{\n"
				+ "  \"package_version\": \"v1\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"suite_version\": \"stage5_v1\",\n"
				+ "  \"created_at\": \"2026-03-28T00:00:00Z\",\n"
				+ "  \"generator_version\": \"gold_stage1_v1\",\n"
				+ "  \"files\": {\n"
				+ "    \"source_manifest\": \"source_manifest.json\",\n"
				+ "    \"normalized_documents\": \"normalized_documents.jsonl\",\n"
				+ "    \"authoring_blocks\": \"authoring_blocks.jsonl\",\n"
				+ "    \"block_links\": \"block_links.jsonl\",\n"
				+ "    \"samples\": \"samples.jsonl\",\n"
				+ "    \"sample_generation_trace\": \"sample_generation_trace.jsonl\",\n"
				+ "    \"gold_package_manifest\": \"gold_package_manifest.json\",\n"
				+ "    \"review_markdown\": \"review.md\"\n"
				+ "  }\n"
				+ "}\n"
		);
		Files.writeString(dir.resolve("review.md"), "# review\n");
		return dir;
	}

	private static final class FakeStreamResponse implements StreamResponse<ChatCompletionChunk> {
		private final java.util.stream.Stream<ChatCompletionChunk> stream;

		private FakeStreamResponse(ChatCompletionChunk chunk) {
			this.stream = java.util.stream.Stream.of(chunk);
		}

		@Override
		public java.util.stream.Stream<ChatCompletionChunk> stream() {
			return stream;
		}

		@Override
		public void close() {
		}
	}

	private static final class StreamResponseTestData {
		private static ChatCompletionChunk toolCallChunk() {
			ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
				.name("search_knowledge_base")
				.arguments("{\"query\":\"Benchmark evidence body\"}")
				.build();

			ChatCompletionChunk.Choice.Delta.ToolCall tc = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
				.index(0)
				.id("call_search")
				.function(fn)
				.build();

			ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
				.toolCalls(java.util.Collections.singletonList(tc))
				.build();

			ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
				.index(0)
				.delta(delta)
				.finishReason(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)
				.build();

			return ChatCompletionChunk.builder()
				.id("chunk-tool")
				.created(0)
				.model("gpt-test")
				.object_(JsonValue.from("chat.completion.chunk"))
				.addChoice(choice)
				.build();
		}

		private static ChatCompletionChunk textChunk(String text) {
			ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
				.content(text)
				.build();

			ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
				.index(0)
				.delta(delta)
				.finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
				.build();

			return ChatCompletionChunk.builder()
				.id("chunk-text")
				.created(0)
				.model("gpt-test")
				.object_(JsonValue.from("chat.completion.chunk"))
				.addChoice(choice)
				.build();
		}
	}
}
