package com.agenticrag.app.ingest.docreader;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class DocreaderReadResponse {
	@JsonProperty("markdownContent")
	private String markdownContent;

	@JsonProperty("imageRefs")
	private List<ImageRef> imageRefs;

	private Map<String, String> metadata;
	private String error;

	public String getMarkdownContent() {
		return markdownContent;
	}

	public void setMarkdownContent(String markdownContent) {
		this.markdownContent = markdownContent;
	}

	public List<ImageRef> getImageRefs() {
		return imageRefs;
	}

	public void setImageRefs(List<ImageRef> imageRefs) {
		this.imageRefs = imageRefs;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadata = metadata;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public static class ImageRef {
		@JsonProperty("originalRef")
		private String originalRef;

		@JsonProperty("fileName")
		private String fileName;

		@JsonProperty("mimeType")
		private String mimeType;

		@JsonProperty("bytesBase64")
		private String bytesBase64;

		public String getOriginalRef() {
			return originalRef;
		}

		public void setOriginalRef(String originalRef) {
			this.originalRef = originalRef;
		}

		public String getFileName() {
			return fileName;
		}

		public void setFileName(String fileName) {
			this.fileName = fileName;
		}

		public String getMimeType() {
			return mimeType;
		}

		public void setMimeType(String mimeType) {
			this.mimeType = mimeType;
		}

		public String getBytesBase64() {
			return bytesBase64;
		}

		public void setBytesBase64(String bytesBase64) {
			this.bytesBase64 = bytesBase64;
		}
	}
}
