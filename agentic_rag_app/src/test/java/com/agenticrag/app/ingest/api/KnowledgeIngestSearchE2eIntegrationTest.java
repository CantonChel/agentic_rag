package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import com.agenticrag.app.ingest.service.ParseJobService;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
class KnowledgeIngestSearchE2eIntegrationTest {
	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ToolRouter toolRouter;

	@Autowired
	private ParseJobService parseJobService;

	@Autowired
	private KnowledgeRepository knowledgeRepository;

	@Autowired
	private KnowledgeBaseRepository knowledgeBaseRepository;

	@Autowired
	private ParseJobRepository parseJobRepository;

	@Autowired
	private ChunkRepository chunkRepository;

	@Autowired
	private EmbeddingRepository embeddingRepository;

	@Autowired
	private CallbackEventRepository callbackEventRepository;

	@MockBean
	private DocumentParseQueue documentParseQueue;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
		callbackEventRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		parseJobRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();

		Mockito.when(embeddingModel.embedTexts(Mockito.anyList())).thenAnswer(invocation -> {
			List<?> texts = invocation.getArgument(0);
			List<List<Double>> out = new ArrayList<>();
			for (int i = 0; i < texts.size(); i++) {
				List<Double> vec = new ArrayList<>();
				vec.add(0.1);
				vec.add(0.2);
				out.add(vec);
			}
			return out;
		});
	}

	@Test
	void ingestAndSearchViaKnowledgeTool() throws Exception {
		String kbId = "kb-e2e";
		String uniqueToken = "e2e-rag-token-12345";
		String content = "hello " + uniqueToken + " from async ingest";

		MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
		multipart.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "e2e.txt";
			}
		});
		multipart.add("metadata", "{\"source\":\"e2e\"}");
		byte[] uploadBody = webTestClient.post()
			.uri("/api/knowledge-bases/{kbId}/knowledge/file", kbId)
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(BodyInserters.fromMultipartData(multipart))
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.returnResult()
			.getResponseBodyContent();

		Assertions.assertNotNull(uploadBody);
		JsonNode uploadJson = objectMapper.readTree(uploadBody);
		String knowledgeId = uploadJson.path("knowledgeId").asText();
		String jobId = uploadJson.path("jobId").asText();
		Assertions.assertFalse(knowledgeId.isEmpty());
		Assertions.assertFalse(jobId.isEmpty());

		parseJobService.tryMarkDispatched(jobId, Instant.now().plusSeconds(300));
		parseJobService.markParsing(jobId, Instant.now().plusSeconds(300));

		Map<String, Object> chunk = new HashMap<>();
		chunk.put("chunk_id", "e2e-0");
		chunk.put("type", "text");
		chunk.put("seq", 0);
		chunk.put("start", 0);
		chunk.put("end", content.length());
		chunk.put("content", content);
		chunk.put("metadata", new HashMap<>());
		List<Map<String, Object>> chunks = new ArrayList<>();
		chunks.add(chunk);

		Map<String, Object> callback = new HashMap<>();
		callback.put("event_id", "evt-" + System.nanoTime());
		callback.put("status", "completed");
		callback.put("message", "ok");
		callback.put("chunks", chunks);
		String callbackBody = objectMapper.writeValueAsString(callback);

		webTestClient.post()
			.uri("/internal/docreader/jobs/{jobId}/result", jobId)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(callbackBody)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.ok").isEqualTo(true)
			.jsonPath("$.state").isEqualTo("success");

		webTestClient.get()
			.uri("/api/jobs/{jobId}", jobId)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("success");

		Tool tool = toolRouter.getTool("search_knowledge_base").orElseThrow();
		ObjectNode args = objectMapper.createObjectNode().put("query", uniqueToken);
		ToolResult result = Mono.from(tool.execute(args, new ToolExecutionContext("req-e2e"))).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertNotNull(result.getOutput());
		Assertions.assertTrue(result.getOutput().contains(uniqueToken));
	}
}
