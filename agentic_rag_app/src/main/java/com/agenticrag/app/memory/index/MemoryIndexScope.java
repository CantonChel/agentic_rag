package com.agenticrag.app.memory.index;

import java.util.Objects;

public class MemoryIndexScope {
	private final MemoryIndexScopeType type;
	private final String id;

	public MemoryIndexScope(MemoryIndexScopeType type, String id) {
		this.type = type != null ? type : MemoryIndexScopeType.USER;
		this.id = id != null ? id.trim() : "";
	}

	public MemoryIndexScopeType getType() {
		return type;
	}

	public String getId() {
		return id;
	}

	public String getTypeValue() {
		return type.getValue();
	}

	public String toKey() {
		return getTypeValue() + ":" + id;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof MemoryIndexScope)) {
			return false;
		}
		MemoryIndexScope that = (MemoryIndexScope) other;
		return type == that.type && Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, id);
	}

	@Override
	public String toString() {
		return toKey();
	}
}
