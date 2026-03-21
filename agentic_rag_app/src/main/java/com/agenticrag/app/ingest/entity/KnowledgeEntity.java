package com.agenticrag.app.ingest.entity;

import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "knowledge")
public class KnowledgeEntity {
	@Id
	@Column(nullable = false, length = 64)
	private String id;

	@Column(nullable = false, length = 64)
	private String knowledgeBaseId;

	@Column(nullable = false, length = 255)
	private String fileName;

	@Column(nullable = false, length = 32)
	private String fileType;

	@Column(nullable = false)
	private long fileSize;

	@Column(nullable = false, length = 64)
	private String fileHash;

	@Column(nullable = false, length = 2048)
	private String filePath;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private KnowledgeParseStatus parseStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private KnowledgeEnableStatus enableStatus;

	@Column
	private String metadataJson;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

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

	public String getFileType() {
		return fileType;
	}

	public void setFileType(String fileType) {
		this.fileType = fileType;
	}

	public long getFileSize() {
		return fileSize;
	}

	public void setFileSize(long fileSize) {
		this.fileSize = fileSize;
	}

	public String getFileHash() {
		return fileHash;
	}

	public void setFileHash(String fileHash) {
		this.fileHash = fileHash;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public KnowledgeParseStatus getParseStatus() {
		return parseStatus;
	}

	public void setParseStatus(KnowledgeParseStatus parseStatus) {
		this.parseStatus = parseStatus;
	}

	public KnowledgeEnableStatus getEnableStatus() {
		return enableStatus;
	}

	public void setEnableStatus(KnowledgeEnableStatus enableStatus) {
		this.enableStatus = enableStatus;
	}

	public String getMetadataJson() {
		return metadataJson;
	}

	public void setMetadataJson(String metadataJson) {
		this.metadataJson = metadataJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
