package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.docreader.DocreaderClient;
import com.agenticrag.app.ingest.docreader.DocreaderReadResponse;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import com.agenticrag.app.ingest.service.DocumentParseDispatcher;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
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
	private DocumentParseDispatcher documentParseDispatcher;

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

	@MockBean
	private DocumentParseQueue documentParseQueue;

	@MockBean
	private DocreaderClient docreaderClient;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
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

		DocreaderReadResponse readResponse = new DocreaderReadResponse();
		readResponse.setMarkdownContent(content);
		readResponse.setImageRefs(Collections.emptyList());
		readResponse.setMetadata(Collections.singletonMap("source", "e2e"));
		readResponse.setError("");
		Mockito.when(docreaderClient.readDocument(Mockito.any())).thenReturn(readResponse);

		documentParseDispatcher.dispatch(new ReservedJob(jobId, null));

		byte[] statusBody = webTestClient.get()
			.uri("/api/jobs/{jobId}", jobId)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.returnResult()
			.getResponseBodyContent();
		Assertions.assertNotNull(statusBody);
		JsonNode statusJson = objectMapper.readTree(statusBody);
		Assertions.assertEquals("success", statusJson.path("status").asText());

		Tool tool = toolRouter.getTool("search_knowledge_base").orElseThrow();
		ObjectNode args = objectMapper.createObjectNode().put("query", uniqueToken);
		ToolResult result = Mono.from(tool.execute(args, new ToolExecutionContext("req-e2e"))).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertNotNull(result.getOutput());
		Assertions.assertTrue(result.getOutput().contains(uniqueToken));
	}

	@Test
	void ingestShouldReplaceImageRefsAndExposeStoredImage() throws Exception {
		String kbId = "kb-image";
		String uploadContent = "placeholder";
		String markdown = "before\n\n![diagram](images/demo.png)\n\nafter";
		byte[] imageBytes = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

		MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
		multipart.add("file", new ByteArrayResource(uploadContent.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "image.txt";
			}
		});
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

		DocreaderReadResponse.ImageRef imageRef = new DocreaderReadResponse.ImageRef();
		imageRef.setOriginalRef("images/demo.png");
		imageRef.setFileName("demo.png");
		imageRef.setMimeType("image/png");
		imageRef.setBytesBase64(Base64.getEncoder().encodeToString(imageBytes));

		DocreaderReadResponse readResponse = new DocreaderReadResponse();
		readResponse.setMarkdownContent(markdown);
		readResponse.setImageRefs(Collections.singletonList(imageRef));
		readResponse.setMetadata(Collections.singletonMap("source", "image"));
		readResponse.setError("");
		Mockito.when(docreaderClient.readDocument(Mockito.any())).thenReturn(readResponse);

		documentParseDispatcher.dispatch(new ReservedJob(jobId, null));

		List<ChunkEntity> chunks = chunkRepository.findByKnowledgeIdOrderByChunkIndexAsc(knowledgeId);
		Assertions.assertFalse(chunks.isEmpty());
		Assertions.assertTrue(chunks.get(0).getContent().contains("/api/knowledge/images?filePath="));
		Assertions.assertNotNull(chunks.get(0).getImageInfoJson());

		JsonNode imageInfo = objectMapper.readTree(chunks.get(0).getImageInfoJson()).get(0);
		String filePath = imageInfo.path("filePath").asText();
		Assertions.assertFalse(filePath.isEmpty());

		webTestClient.get()
			.uri("/api/knowledge/{knowledgeId}/chunks", knowledgeId)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].imageInfos[0].filePath").isEqualTo(filePath);

		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/knowledge/images").queryParam("filePath", filePath).build())
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentType("image/png")
			.expectBody(byte[].class).isEqualTo(imageBytes);
	}
}
