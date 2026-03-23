package com.agenticrag.app.ingest.docreader;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class DocreaderCallbackRequest {
	@JsonProperty("event_id")
	private String eventId;
	private String status;
	private String message;
	private ErrorInfo error;
	private List<ChunkPayload> chunks;

	public String getEventId() {
		return eventId;
	}

	public void setEventId(String eventId) {
		this.eventId = eventId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public ErrorInfo getError() {
		return error;
	}

	public void setError(ErrorInfo error) {
		this.error = error;
	}

	public List<ChunkPayload> getChunks() {
		return chunks;
	}

	public void setChunks(List<ChunkPayload> chunks) {
		this.chunks = chunks;
	}

	public static class ErrorInfo {
		private String code;
		private String message;

		public String getCode() {
			return code;
		}

		public void setCode(String code) {
			this.code = code;
		}

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}
	}

	public static class ChunkPayload {
		@JsonProperty("chunk_id")
		private String chunkId;
		private String type;
		private Integer seq;
		private Integer start;
		private Integer end;
		private String content;

		@JsonProperty("image_info")
		private List<ImageInfoPayload> imageInfo;

		private Map<String, Object> metadata;

		public String getChunkId() {
			return chunkId;
		}

		public void setChunkId(String chunkId) {
			this.chunkId = chunkId;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public Integer getSeq() {
			return seq;
		}

		public void setSeq(Integer seq) {
			this.seq = seq;
		}

		public Integer getStart() {
			return start;
		}

		public void setStart(Integer start) {
			this.start = start;
		}

		public Integer getEnd() {
			return end;
		}

		public void setEnd(Integer end) {
			this.end = end;
		}

		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}

		public List<ImageInfoPayload> getImageInfo() {
			return imageInfo;
		}

		public void setImageInfo(List<ImageInfoPayload> imageInfo) {
			this.imageInfo = imageInfo;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}

		public void setMetadata(Map<String, Object> metadata) {
			this.metadata = metadata;
		}
	}

	public static class ImageInfoPayload {
		private String url;

		@JsonProperty("original_url")
		private String originalUrl;

		@JsonProperty("start_pos")
		private Integer startPos;

		@JsonProperty("end_pos")
		private Integer endPos;

		private String caption;

		@JsonProperty("ocr_text")
		private String ocrText;

		@JsonProperty("storage_bucket")
		private String storageBucket;

		@JsonProperty("storage_key")
		private String storageKey;

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getOriginalUrl() {
			return originalUrl;
		}

		public void setOriginalUrl(String originalUrl) {
			this.originalUrl = originalUrl;
		}

		public Integer getStartPos() {
			return startPos;
		}

		public void setStartPos(Integer startPos) {
			this.startPos = startPos;
		}

		public Integer getEndPos() {
			return endPos;
		}

		public void setEndPos(Integer endPos) {
			this.endPos = endPos;
		}

		public String getCaption() {
			return caption;
		}

		public void setCaption(String caption) {
			this.caption = caption;
		}

		public String getOcrText() {
			return ocrText;
		}

		public void setOcrText(String ocrText) {
			this.ocrText = ocrText;
		}

		public String getStorageBucket() {
			return storageBucket;
		}

		public void setStorageBucket(String storageBucket) {
			this.storageBucket = storageBucket;
		}

		public String getStorageKey() {
			return storageKey;
		}

		public void setStorageKey(String storageKey) {
			this.storageKey = storageKey;
		}
	}
}
