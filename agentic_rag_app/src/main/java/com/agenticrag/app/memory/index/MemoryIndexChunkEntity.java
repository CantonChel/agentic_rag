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
	name = "memory_index_chunks",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_memory_index_chunks_scope_path_block_lines_hash",
			columnNames = {"scope_type", "scope_id", "path", "block_id", "line_start", "line_end", "chunk_hash"}
		)
	},
	indexes = {
		@Index(name = "idx_memory_index_chunks_scope", columnList = "scope_type,scope_id"),
		@Index(name = "idx_memory_index_chunks_scope_path", columnList = "scope_type,scope_id,path"),
		@Index(name = "idx_memory_index_chunks_chunk_hash", columnList = "chunk_hash")
	}
)
public class MemoryIndexChunkEntity {
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

	@Column(name = "block_id", length = 128)
	private String blockId;

	@Column(name = "line_start", nullable = false)
	private int lineStart;

	@Column(name = "line_end", nullable = false)
	private int lineEnd;

	@Column(name = "chunk_hash", nullable = false, length = 128)
	private String chunkHash;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

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

	public String getBlockId() {
		return blockId;
	}

	public void setBlockId(String blockId) {
		this.blockId = blockId;
	}

	public int getLineStart() {
		return lineStart;
	}

	public void setLineStart(int lineStart) {
		this.lineStart = lineStart;
	}

	public int getLineEnd() {
		return lineEnd;
	}

	public void setLineEnd(int lineEnd) {
		this.lineEnd = lineEnd;
	}

	public String getChunkHash() {
		return chunkHash;
	}

	public void setChunkHash(String chunkHash) {
		this.chunkHash = chunkHash;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
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
