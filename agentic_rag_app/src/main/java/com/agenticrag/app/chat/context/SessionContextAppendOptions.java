package com.agenticrag.app.chat.context;

public final class SessionContextAppendOptions {
	private static final SessionContextAppendOptions DEFAULTS = new SessionContextAppendOptions(true);
	private static final SessionContextAppendOptions WITHOUT_PRE_COMPACTION_FLUSH = new SessionContextAppendOptions(false);

	private final boolean allowPreCompactionFlush;

	private SessionContextAppendOptions(boolean allowPreCompactionFlush) {
		this.allowPreCompactionFlush = allowPreCompactionFlush;
	}

	public static SessionContextAppendOptions defaults() {
		return DEFAULTS;
	}

	public static SessionContextAppendOptions withoutPreCompactionFlush() {
		return WITHOUT_PRE_COMPACTION_FLUSH;
	}

	public boolean isAllowPreCompactionFlush() {
		return allowPreCompactionFlush;
	}
}
