package com.agenticrag.app.chat.context;

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
	name = "session_context_messages",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_session_context_scoped_idx", columnNames = { "scoped_session_id", "message_index" })
	},
	indexes = {
		@Index(name = "idx_session_context_scoped_session_id", columnList = "scoped_session_id"),
		@Index(name = "idx_session_context_user_id", columnList = "user_id")
	}
)
public class SessionContextMessageEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "session_id", nullable = false)
	private String sessionId;

	@Column(name = "scoped_session_id", nullable = false)
	private String scopedSessionId;

	@Column(name = "message_index", nullable = false)
	private Integer messageIndex;

	@Column(name = "type", nullable = false)
	private String type;

	@Column(name = "content", columnDefinition = "text")
	private String content;

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

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getScopedSessionId() {
		return scopedSessionId;
	}

	public void setScopedSessionId(String scopedSessionId) {
		this.scopedSessionId = scopedSessionId;
	}

	public Integer getMessageIndex() {
		return messageIndex;
	}

	public void setMessageIndex(Integer messageIndex) {
		this.messageIndex = messageIndex;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
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
