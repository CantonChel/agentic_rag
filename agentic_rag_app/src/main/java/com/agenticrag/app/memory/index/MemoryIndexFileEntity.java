package com.agenticrag.app.memory.index;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(
	name = "memory_index_files",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_memory_index_files_scope_path", columnNames = {"scope_type", "scope_id", "path"})
	},
	indexes = {
		@Index(name = "idx_memory_index_files_scope", columnList = "scope_type,scope_id"),
		@Index(name = "idx_memory_index_files_content_hash", columnList = "content_hash")
	}
)
public class MemoryIndexFileEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "scope_type", nullable = false, length = 16)
	private String scopeType;

	@Column(name = "scope_id", nullable = false, length = 128)
	private String scopeId;

	@Column(name = "path", nullable = false, length = 1024)
	private String path;

	@Column(name = "kind", nullable = false, length = 32)
	private String kind;

	@Column(name = "content_hash", nullable = false, length = 128)
	private String contentHash;

	@Column(name = "file_mtime")
	private Instant fileMtime;

	@Column(name = "indexed_at", nullable = false)
	private Instant indexedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getScopeType() {
		return scopeType;
	}

	public void setScopeType(String scopeType) {
		this.scopeType = scopeType;
	}

	public String getScopeId() {
		return scopeId;
	}

	public void setScopeId(String scopeId) {
		this.scopeId = scopeId;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public Instant getFileMtime() {
		return fileMtime;
	}

	public void setFileMtime(Instant fileMtime) {
		this.fileMtime = fileMtime;
	}

	public Instant getIndexedAt() {
		return indexedAt;
	}

	public void setIndexedAt(Instant indexedAt) {
		this.indexedAt = indexedAt;
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
