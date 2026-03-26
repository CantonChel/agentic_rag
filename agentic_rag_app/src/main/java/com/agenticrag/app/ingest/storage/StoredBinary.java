package com.agenticrag.app.ingest.storage;

public class StoredBinary {
	private final byte[] bytes;
	private final String contentType;

	public StoredBinary(byte[] bytes, String contentType) {
		this.bytes = bytes != null ? bytes : new byte[0];
		this.contentType = contentType != null ? contentType : "application/octet-stream";
	}

	public byte[] getBytes() {
		return bytes;
	}

	public String getContentType() {
		return contentType;
	}
}
