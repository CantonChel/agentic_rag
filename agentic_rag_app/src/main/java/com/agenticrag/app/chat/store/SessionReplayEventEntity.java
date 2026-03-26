package com.agenticrag.app.chat.store;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "session_replay_events")
public class SessionReplayEventEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String sessionId;

	@Column(nullable = false)
	private String type;

	@Column(columnDefinition = "text")
	private String content;

	@Column
	private String finishReason;

	@Column
	private String source;

	@Column
	private String originModel;

	@Column
	private Integer roundId;

	@Column
	private String turnId;

	@Column
	private Long sequenceId;

	@Column
	private Long eventTs;

	@Column
	private String toolCallId;

	@Column
	private String toolName;

	@Column
	private String status;

	@Column
	private Long durationMs;

	@Column(columnDefinition = "text")
	private String argsPreviewJson;

	@Column(columnDefinition = "text")
	private String resultPreviewJson;

	@Column(columnDefinition = "text")
	private String error;

	@Column(nullable = false)
	private Instant createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
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

	public String getFinishReason() {
		return finishReason;
	}

	public void setFinishReason(String finishReason) {
		this.finishReason = finishReason;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getOriginModel() {
		return originModel;
	}

	public void setOriginModel(String originModel) {
		this.originModel = originModel;
	}

	public Integer getRoundId() {
		return roundId;
	}

	public void setRoundId(Integer roundId) {
		this.roundId = roundId;
	}

	public String getTurnId() {
		return turnId;
	}

	public void setTurnId(String turnId) {
		this.turnId = turnId;
	}

	public Long getSequenceId() {
		return sequenceId;
	}

	public void setSequenceId(Long sequenceId) {
		this.sequenceId = sequenceId;
	}

	public Long getEventTs() {
		return eventTs;
	}

	public void setEventTs(Long eventTs) {
		this.eventTs = eventTs;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public void setToolCallId(String toolCallId) {
		this.toolCallId = toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public String getArgsPreviewJson() {
		return argsPreviewJson;
	}

	public void setArgsPreviewJson(String argsPreviewJson) {
		this.argsPreviewJson = argsPreviewJson;
	}

	public String getResultPreviewJson() {
		return resultPreviewJson;
	}

	public void setResultPreviewJson(String resultPreviewJson) {
		this.resultPreviewJson = resultPreviewJson;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
