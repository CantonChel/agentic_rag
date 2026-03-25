package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.entity.KnowledgeBaseEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import com.agenticrag.app.ingest.storage.KnowledgeFileStorageService;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeCrudService {
	private final KnowledgeBaseRepository knowledgeBaseRepository;
	private final KnowledgeRepository knowledgeRepository;
	private final ChunkRepository chunkRepository;
	private final EmbeddingRepository embeddingRepository;
	private final ParseJobRepository parseJobRepository;
	private final CallbackEventRepository callbackEventRepository;
	private final KnowledgeFileStorageService fileStorageService;
	private final List<ChunkIndexer> chunkIndexers;
	private final ObjectMapper objectMapper;

	public KnowledgeCrudService(
		KnowledgeBaseRepository knowledgeBaseRepository,
		KnowledgeRepository knowledgeRepository,
		ChunkRepository chunkRepository,
		EmbeddingRepository embeddingRepository,
		ParseJobRepository parseJobRepository,
		CallbackEventRepository callbackEventRepository,
		KnowledgeFileStorageService fileStorageService,
		List<ChunkIndexer> chunkIndexers,
		ObjectMapper objectMapper
	) {
		this.knowledgeBaseRepository = knowledgeBaseRepository;
		this.knowledgeRepository = knowledgeRepository;
		this.chunkRepository = chunkRepository;
		this.embeddingRepository = embeddingRepository;
		this.parseJobRepository = parseJobRepository;
		this.callbackEventRepository = callbackEventRepository;
		this.fileStorageService = fileStorageService;
		this.chunkIndexers = chunkIndexers;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void ensureKnowledgeBaseExists(String knowledgeBaseId) {
		String kbId = normalize(knowledgeBaseId, "");
		if (kbId.isEmpty()) {
			return;
		}
		if (knowledgeBaseRepository.existsById(kbId)) {
			return;
		}
		Instant now = Instant.now();
		KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
		kb.setId(kbId);
		kb.setName(kbId);
		kb.setDescription(null);
		kb.setEnabled(true);
		kb.setCreatedAt(now);
		kb.setUpdatedAt(now);
		try {
			knowledgeBaseRepository.save(kb);
		} catch (DataIntegrityViolationException ignored) {
			// Another concurrent writer may have created it.
		}
	}

	@Transactional(readOnly = true)
	public KnowledgeBaseView getKnowledgeBase(String knowledgeBaseId) {
		String kbId = normalize(knowledgeBaseId, "");
		if (kbId.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId is required");
		}
		long count = knowledgeRepository.countByKnowledgeBaseId(kbId);
		KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
		if (kb == null && count <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge base not found");
		}
		if (kb == null) {
			return new KnowledgeBaseView(kbId, kbId, null, true, count, null, null);
		}
		return toKnowledgeBaseView(kb, count);
	}

	@Transactional
	public KnowledgeBaseView createKnowledgeBase(CreateKnowledgeBaseRequest request) {
		String kbId = normalize(request != null ? request.getKnowledgeBaseId() : null, "");
		if (kbId.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId is required");
		}
		if (knowledgeBaseRepository.existsById(kbId) || knowledgeRepository.countByKnowledgeBaseId(kbId) > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "knowledge base already exists");
		}
		Instant now = Instant.now();
		KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
		kb.setId(kbId);
		kb.setName(normalize(request != null ? request.getName() : null, kbId));
		kb.setDescription(normalizeNullable(request != null ? request.getDescription() : null));
		kb.setEnabled(request == null || request.getEnabled() == null || request.getEnabled());
		kb.setCreatedAt(now);
		kb.setUpdatedAt(now);
		knowledgeBaseRepository.save(kb);
		return toKnowledgeBaseView(kb, 0L);
	}

	@Transactional
	public KnowledgeBaseView updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
		String sourceKbId = normalize(knowledgeBaseId, "");
		if (sourceKbId.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId is required");
		}

		KnowledgeBaseEntity existing = knowledgeBaseRepository.findById(sourceKbId).orElse(null);
		long sourceDocCount = knowledgeRepository.countByKnowledgeBaseId(sourceKbId);
		if (existing == null && sourceDocCount <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge base not found");
		}

		String targetKbId = normalize(request != null ? request.getKnowledgeBaseId() : null, sourceKbId);
		if (targetKbId.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId cannot be empty");
		}
		boolean idChanged = !targetKbId.equals(sourceKbId);
		if (idChanged && (knowledgeBaseRepository.existsById(targetKbId) || knowledgeRepository.countByKnowledgeBaseId(targetKbId) > 0)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "target knowledge base already exists");
		}

		Instant now = Instant.now();
		KnowledgeBaseEntity updated = existing != null ? existing : new KnowledgeBaseEntity();
		updated.setId(sourceKbId);
		updated.setName(normalize(
			request != null && request.getName() != null ? request.getName() : (existing != null ? existing.getName() : sourceKbId),
			sourceKbId
		));
		if (request != null && request.getDescription() != null) {
			updated.setDescription(normalizeNullable(request.getDescription()));
		} else if (existing == null) {
			updated.setDescription(null);
		}
		boolean enabled = existing != null ? existing.isEnabled() : true;
		if (request != null && request.getEnabled() != null) {
			enabled = request.getEnabled();
		}
		updated.setEnabled(enabled);
		updated.setCreatedAt(existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : now);
		updated.setUpdatedAt(now);

		if (idChanged) {
			KnowledgeBaseEntity moved = new KnowledgeBaseEntity();
			moved.setId(targetKbId);
			moved.setName(normalize(request != null ? request.getName() : null, updated.getName()));
			moved.setDescription(updated.getDescription());
			moved.setEnabled(updated.isEnabled());
			moved.setCreatedAt(updated.getCreatedAt());
			moved.setUpdatedAt(now);
			knowledgeBaseRepository.save(moved);
			knowledgeRepository.moveKnowledgeBase(sourceKbId, targetKbId, now);
			if (existing != null) {
				knowledgeBaseRepository.delete(existing);
			}
			long movedCount = knowledgeRepository.countByKnowledgeBaseId(targetKbId);
			return toKnowledgeBaseView(moved, movedCount);
		}

		knowledgeBaseRepository.save(updated);
		long count = knowledgeRepository.countByKnowledgeBaseId(sourceKbId);
		return toKnowledgeBaseView(updated, count);
	}

	@Transactional
	public KnowledgeBaseDeleteResult deleteKnowledgeBase(String knowledgeBaseId) {
		String kbId = normalize(knowledgeBaseId, "");
		if (kbId.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId is required");
		}
		KnowledgeBaseEntity base = knowledgeBaseRepository.findById(kbId).orElse(null);
		List<String> knowledgeIds = knowledgeRepository.listIdsByKnowledgeBaseId(kbId);
		if (base == null && (knowledgeIds == null || knowledgeIds.isEmpty())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge base not found");
		}
		CascadeDeleteCounter counter = new CascadeDeleteCounter();
		for (String knowledgeId : knowledgeIds) {
			deleteKnowledgeInternal(knowledgeId, counter);
		}
		if (base != null) {
			knowledgeBaseRepository.delete(base);
		}
		return new KnowledgeBaseDeleteResult(
			kbId,
			counter.documentsDeleted,
			counter.parseJobsDeleted,
			counter.callbackEventsDeleted,
			counter.chunksDeleted,
			counter.embeddingsDeleted
		);
	}

	@Transactional(readOnly = true)
	public KnowledgeDocumentView getKnowledgeDocument(String knowledgeId) {
		String kid = normalize(knowledgeId, "");
		if (kid.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeId is required");
		}
		KnowledgeEntity knowledge = knowledgeRepository.findById(kid)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge document not found"));
		return toKnowledgeDocumentView(knowledge);
	}

	@Transactional
	public KnowledgeDocumentView updateKnowledgeDocument(String knowledgeId, UpdateKnowledgeDocumentRequest request) {
		String kid = normalize(knowledgeId, "");
		if (kid.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeId is required");
		}
		KnowledgeEntity knowledge = knowledgeRepository.findById(kid)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge document not found"));

		if (request != null && request.getKnowledgeBaseId() != null) {
			String newKbId = normalize(request.getKnowledgeBaseId(), "");
			if (newKbId.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeBaseId cannot be empty");
			}
			ensureKnowledgeBaseExists(newKbId);
			knowledge.setKnowledgeBaseId(newKbId);
		}
		if (request != null && request.getFileName() != null) {
			String fileName = normalize(request.getFileName(), "");
			if (fileName.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileName cannot be empty");
			}
			knowledge.setFileName(fileName);
			knowledge.setFileType(resolveFileType(fileName));
		}
		if (request != null && request.getEnableStatus() != null) {
			knowledge.setEnableStatus(parseEnableStatus(request.getEnableStatus()));
		}
		if (request != null && request.getMetadata() != null) {
			knowledge.setMetadataJson(toJsonMap(request.getMetadata()));
		}
		knowledge.setUpdatedAt(Instant.now());
		KnowledgeEntity saved = knowledgeRepository.save(knowledge);
		return toKnowledgeDocumentView(saved);
	}

	@Transactional
	public KnowledgeDocumentDeleteResult deleteKnowledgeDocument(String knowledgeId) {
		String kid = normalize(knowledgeId, "");
		if (kid.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledgeId is required");
		}
		CascadeDeleteCounter counter = new CascadeDeleteCounter();
		boolean deleted = deleteKnowledgeInternal(kid, counter);
		if (!deleted) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge document not found");
		}
		return new KnowledgeDocumentDeleteResult(
			kid,
			counter.parseJobsDeleted,
			counter.callbackEventsDeleted,
			counter.chunksDeleted,
			counter.embeddingsDeleted
		);
	}

	private boolean deleteKnowledgeInternal(String knowledgeId, CascadeDeleteCounter counter) {
		Optional<KnowledgeEntity> knowledgeOpt = knowledgeRepository.findById(knowledgeId);
		if (!knowledgeOpt.isPresent()) {
			return false;
		}
		KnowledgeEntity knowledge = knowledgeOpt.get();
		List<String> chunkIds = chunkRepository.listChunkIdsByKnowledgeId(knowledgeId);

		counter.embeddingsDeleted += embeddingRepository.deleteByKnowledgeId(knowledgeId);
		counter.chunksDeleted += chunkRepository.deleteByKnowledgeId(knowledgeId);
		if (chunkIds != null && !chunkIds.isEmpty() && chunkIndexers != null) {
			for (ChunkIndexer indexer : chunkIndexers) {
				if (indexer == null) {
					continue;
				}
				indexer.removeChunkIds(chunkIds);
				indexer.removeKnowledge(knowledgeId);
			}
		}

		List<String> jobIds = parseJobRepository.findByKnowledgeId(knowledgeId)
			.stream()
			.map(job -> job != null ? job.getId() : null)
			.filter(id -> id != null && !id.trim().isEmpty())
			.collect(Collectors.toList());
		if (!jobIds.isEmpty()) {
			counter.callbackEventsDeleted += callbackEventRepository.deleteByJobIdIn(jobIds);
		}
		counter.parseJobsDeleted += parseJobRepository.deleteByKnowledgeId(knowledgeId);
		fileStorageService.delete(knowledge.getFilePath());
		knowledgeRepository.delete(knowledge);
		counter.documentsDeleted += 1;
		return true;
	}

	private KnowledgeEnableStatus parseEnableStatus(String status) {
		if (status == null || status.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enableStatus is required");
		}
		String normalized = status.trim().toUpperCase(Locale.ROOT);
		try {
			return KnowledgeEnableStatus.valueOf(normalized);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid enableStatus");
		}
	}

	private String resolveFileType(String fileName) {
		String safe = normalize(fileName, "upload.bin");
		int idx = safe.lastIndexOf('.');
		if (idx <= 0 || idx >= safe.length() - 1) {
			return "bin";
		}
		return safe.substring(idx + 1).toLowerCase(Locale.ROOT);
	}

	private Map<String, Object> parseMetadataJson(String json) {
		if (json == null || json.trim().isEmpty()) {
			return new HashMap<>();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = objectMapper.readValue(json, Map.class);
			return map != null ? map : new HashMap<>();
		} catch (Exception ignored) {
			return new HashMap<>();
		}
	}

	private String toJsonMap(Object metadata) {
		try {
			if (metadata instanceof String) {
				String text = ((String) metadata).trim();
				if (text.isEmpty()) {
					return "{}";
				}
				Map<?, ?> map = objectMapper.readValue(text, Map.class);
				return objectMapper.writeValueAsString(map != null ? map : new HashMap<>());
			}
			Map<?, ?> converted = objectMapper.convertValue(metadata, Map.class);
			return objectMapper.writeValueAsString(converted != null ? converted : new HashMap<>());
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata must be valid JSON object");
		}
	}

	private KnowledgeBaseView toKnowledgeBaseView(KnowledgeBaseEntity kb, long documentCount) {
		return new KnowledgeBaseView(
			kb.getId(),
			kb.getName(),
			kb.getDescription(),
			kb.isEnabled(),
			documentCount,
			kb.getCreatedAt() != null ? kb.getCreatedAt().toString() : null,
			kb.getUpdatedAt() != null ? kb.getUpdatedAt().toString() : null
		);
	}

	private KnowledgeDocumentView toKnowledgeDocumentView(KnowledgeEntity knowledge) {
		KnowledgeParseStatus parseStatus = knowledge.getParseStatus();
		KnowledgeEnableStatus enableStatus = knowledge.getEnableStatus();
		return new KnowledgeDocumentView(
			knowledge.getId(),
			knowledge.getKnowledgeBaseId(),
			knowledge.getFileName(),
			knowledge.getFileType(),
			knowledge.getFileSize(),
			knowledge.getFileHash(),
			knowledge.getFilePath(),
			parseStatus != null ? parseStatus.name().toLowerCase(Locale.ROOT) : "unknown",
			enableStatus != null ? enableStatus.name().toLowerCase(Locale.ROOT) : "unknown",
			parseMetadataJson(knowledge.getMetadataJson()),
			knowledge.getCreatedAt() != null ? knowledge.getCreatedAt().toString() : null,
			knowledge.getUpdatedAt() != null ? knowledge.getUpdatedAt().toString() : null
		);
	}

	private String normalize(String value, String fallback) {
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		return value.trim();
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String out = value.trim();
		return out.isEmpty() ? null : out;
	}

	private static class CascadeDeleteCounter {
		private long documentsDeleted;
		private long parseJobsDeleted;
		private long callbackEventsDeleted;
		private long chunksDeleted;
		private long embeddingsDeleted;
	}

	public static class CreateKnowledgeBaseRequest {
		private String knowledgeBaseId;
		private String name;
		private String description;
		private Boolean enabled;

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public void setKnowledgeBaseId(String knowledgeBaseId) {
			this.knowledgeBaseId = knowledgeBaseId;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public Boolean getEnabled() {
			return enabled;
		}

		public void setEnabled(Boolean enabled) {
			this.enabled = enabled;
		}
	}

	public static class UpdateKnowledgeBaseRequest {
		private String knowledgeBaseId;
		private String name;
		private String description;
		private Boolean enabled;

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public void setKnowledgeBaseId(String knowledgeBaseId) {
			this.knowledgeBaseId = knowledgeBaseId;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public Boolean getEnabled() {
			return enabled;
		}

		public void setEnabled(Boolean enabled) {
			this.enabled = enabled;
		}
	}

	public static class UpdateKnowledgeDocumentRequest {
		private String knowledgeBaseId;
		private String fileName;
		private Object metadata;
		private String enableStatus;

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public void setKnowledgeBaseId(String knowledgeBaseId) {
			this.knowledgeBaseId = knowledgeBaseId;
		}

		public String getFileName() {
			return fileName;
		}

		public void setFileName(String fileName) {
			this.fileName = fileName;
		}

		public Object getMetadata() {
			return metadata;
		}

		public void setMetadata(Object metadata) {
			this.metadata = metadata;
		}

		public String getEnableStatus() {
			return enableStatus;
		}

		public void setEnableStatus(String enableStatus) {
			this.enableStatus = enableStatus;
		}
	}

	public static class KnowledgeBaseView {
		private final String knowledgeBaseId;
		private final String name;
		private final String description;
		private final boolean enabled;
		private final long documentCount;
		private final String createdAt;
		private final String updatedAt;

		public KnowledgeBaseView(
			String knowledgeBaseId,
			String name,
			String description,
			boolean enabled,
			long documentCount,
			String createdAt,
			String updatedAt
		) {
			this.knowledgeBaseId = knowledgeBaseId;
			this.name = name;
			this.description = description;
			this.enabled = enabled;
			this.documentCount = documentCount;
			this.createdAt = createdAt;
			this.updatedAt = updatedAt;
		}

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public String getName() {
			return name;
		}

		public String getDescription() {
			return description;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public long getDocumentCount() {
			return documentCount;
		}

		public String getCreatedAt() {
			return createdAt;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class KnowledgeDocumentView {
		private final String knowledgeId;
		private final String knowledgeBaseId;
		private final String fileName;
		private final String fileType;
		private final long fileSize;
		private final String fileHash;
		private final String filePath;
		private final String parseStatus;
		private final String enableStatus;
		private final Map<String, Object> metadata;
		private final String createdAt;
		private final String updatedAt;

		public KnowledgeDocumentView(
			String knowledgeId,
			String knowledgeBaseId,
			String fileName,
			String fileType,
			long fileSize,
			String fileHash,
			String filePath,
			String parseStatus,
			String enableStatus,
			Map<String, Object> metadata,
			String createdAt,
			String updatedAt
		) {
			this.knowledgeId = knowledgeId;
			this.knowledgeBaseId = knowledgeBaseId;
			this.fileName = fileName;
			this.fileType = fileType;
			this.fileSize = fileSize;
			this.fileHash = fileHash;
			this.filePath = filePath;
			this.parseStatus = parseStatus;
			this.enableStatus = enableStatus;
			this.metadata = metadata;
			this.createdAt = createdAt;
			this.updatedAt = updatedAt;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public String getFileName() {
			return fileName;
		}

		public String getFileType() {
			return fileType;
		}

		public long getFileSize() {
			return fileSize;
		}

		public String getFileHash() {
			return fileHash;
		}

		public String getFilePath() {
			return filePath;
		}

		public String getParseStatus() {
			return parseStatus;
		}

		public String getEnableStatus() {
			return enableStatus;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}

		public String getCreatedAt() {
			return createdAt;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class KnowledgeBaseDeleteResult {
		private final String knowledgeBaseId;
		private final long deletedDocuments;
		private final long deletedParseJobs;
		private final long deletedCallbackEvents;
		private final long deletedChunks;
		private final long deletedEmbeddings;

		public KnowledgeBaseDeleteResult(
			String knowledgeBaseId,
			long deletedDocuments,
			long deletedParseJobs,
			long deletedCallbackEvents,
			long deletedChunks,
			long deletedEmbeddings
		) {
			this.knowledgeBaseId = knowledgeBaseId;
			this.deletedDocuments = deletedDocuments;
			this.deletedParseJobs = deletedParseJobs;
			this.deletedCallbackEvents = deletedCallbackEvents;
			this.deletedChunks = deletedChunks;
			this.deletedEmbeddings = deletedEmbeddings;
		}

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public long getDeletedDocuments() {
			return deletedDocuments;
		}

		public long getDeletedParseJobs() {
			return deletedParseJobs;
		}

		public long getDeletedCallbackEvents() {
			return deletedCallbackEvents;
		}

		public long getDeletedChunks() {
			return deletedChunks;
		}

		public long getDeletedEmbeddings() {
			return deletedEmbeddings;
		}
	}

	public static class KnowledgeDocumentDeleteResult {
		private final String knowledgeId;
		private final long deletedParseJobs;
		private final long deletedCallbackEvents;
		private final long deletedChunks;
		private final long deletedEmbeddings;

		public KnowledgeDocumentDeleteResult(
			String knowledgeId,
			long deletedParseJobs,
			long deletedCallbackEvents,
			long deletedChunks,
			long deletedEmbeddings
		) {
			this.knowledgeId = knowledgeId;
			this.deletedParseJobs = deletedParseJobs;
			this.deletedCallbackEvents = deletedCallbackEvents;
			this.deletedChunks = deletedChunks;
			this.deletedEmbeddings = deletedEmbeddings;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public long getDeletedParseJobs() {
			return deletedParseJobs;
		}

		public long getDeletedCallbackEvents() {
			return deletedCallbackEvents;
		}

		public long getDeletedChunks() {
			return deletedChunks;
		}

		public long getDeletedEmbeddings() {
			return deletedEmbeddings;
		}
	}
}
