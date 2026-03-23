package com.agenticrag.app.session;

public final class SessionScope {
	private static final String DEFAULT_USER_ID = "anonymous";
	private static final String DEFAULT_SESSION_ID = "default";
	private static final String SEPARATOR = "::";

	private SessionScope() {
	}

	public static String normalizeUserId(String userId) {
		if (userId == null || userId.trim().isEmpty()) {
			return DEFAULT_USER_ID;
		}
		return userId.trim();
	}

	public static String normalizeSessionId(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return DEFAULT_SESSION_ID;
		}
		return sessionId.trim();
	}

	public static String scopedSessionId(String userId, String sessionId) {
		String uid = normalizeUserId(userId);
		String sid = normalizeSessionId(sessionId);
		return uid + SEPARATOR + sid;
	}

	public static String userIdFromScopedSessionId(String scopedSessionId) {
		String scoped = normalizeSessionId(scopedSessionId);
		int idx = scoped.indexOf(SEPARATOR);
		if (idx < 0) {
			return DEFAULT_USER_ID;
		}
		String uid = scoped.substring(0, idx);
		return normalizeUserId(uid);
	}

	public static String sessionIdFromScopedSessionId(String scopedSessionId) {
		String scoped = normalizeSessionId(scopedSessionId);
		int idx = scoped.indexOf(SEPARATOR);
		if (idx < 0) {
			return scoped;
		}
		String sid = scoped.substring(idx + SEPARATOR.length());
		return normalizeSessionId(sid);
	}
}
