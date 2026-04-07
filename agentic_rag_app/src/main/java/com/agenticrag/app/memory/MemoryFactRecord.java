package com.agenticrag.app.memory;

public class MemoryFactRecord {
	private final MemoryFactBucket bucket;
	private final String subject;
	private final String attribute;
	private final String value;
	private final String statement;
	private final String factKey;

	public MemoryFactRecord(
		MemoryFactBucket bucket,
		String subject,
		String attribute,
		String value,
		String statement,
		String factKey
	) {
		this.bucket = bucket;
		this.subject = subject;
		this.attribute = attribute;
		this.value = value;
		this.statement = statement;
		this.factKey = factKey;
	}

	public MemoryFactBucket getBucket() {
		return bucket;
	}

	public String getSubject() {
		return subject;
	}

	public String getAttribute() {
		return attribute;
	}

	public String getValue() {
		return value;
	}

	public String getStatement() {
		return statement;
	}

	public String getFactKey() {
		return factKey;
	}
}
