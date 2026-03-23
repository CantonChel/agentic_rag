package com.agenticrag.app.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionScopeTest {
	@Test
	void buildsAndParsesScopedSessionId() {
		String scoped = SessionScope.scopedSessionId("u1", "s1");
		Assertions.assertEquals("u1::s1", scoped);
		Assertions.assertEquals("u1", SessionScope.userIdFromScopedSessionId(scoped));
		Assertions.assertEquals("s1", SessionScope.sessionIdFromScopedSessionId(scoped));
	}

	@Test
	void fallsBackToAnonymousForUnscopedSession() {
		Assertions.assertEquals("anonymous", SessionScope.userIdFromScopedSessionId("s-raw"));
		Assertions.assertEquals("s-raw", SessionScope.sessionIdFromScopedSessionId("s-raw"));
	}

	@Test
	void normalizesBlankInputs() {
		Assertions.assertEquals("anonymous", SessionScope.normalizeUserId(" "));
		Assertions.assertEquals("default", SessionScope.normalizeSessionId(" "));
		Assertions.assertEquals("anonymous::default", SessionScope.scopedSessionId(" ", " "));
	}
}
