package com.agenticrag.app.memory.index;

import java.io.Serializable;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Embeddable;

@Embeddable
public class MemoryIndexMetaId implements Serializable {
	@Column(name = "scope_type", nullable = false, length = 16)
	private String scopeType;

	@Column(name = "scope_id", nullable = false, length = 128)
	private String scopeId;

	public MemoryIndexMetaId() {
	}

	public MemoryIndexMetaId(String scopeType, String scopeId) {
		this.scopeType = scopeType;
		this.scopeId = scopeId;
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

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof MemoryIndexMetaId)) {
			return false;
		}
		MemoryIndexMetaId that = (MemoryIndexMetaId) other;
		return Objects.equals(scopeType, that.scopeType) && Objects.equals(scopeId, that.scopeId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(scopeType, scopeId);
	}
}
