package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexScope;
import com.agenticrag.app.memory.index.MemoryIndexScopeType;
import com.agenticrag.app.memory.index.MemoryIndexSearchRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryIndexSearchRepositoryTest {
	@Test
	void searchLexicalUsesSingleCharacterEscapeForLikeClause() {
		AtomicReference<String> capturedSql = new AtomicReference<>();
		AtomicReference<Object[]> capturedArgs = new AtomicReference<>();
		JdbcTemplate jdbcTemplate = new JdbcTemplate() {
			@Override
			public List<Map<String, Object>> queryForList(String sql, Object... args) {
				capturedSql.set(sql);
				capturedArgs.set(args);
				return new ArrayList<>();
			}
		};
		MemoryIndexSearchRepository repository = new MemoryIndexSearchRepository(jdbcTemplate);

		List<?> results = repository.searchLexical(
			List.of(
				new MemoryIndexScope(MemoryIndexScopeType.GLOBAL, "__global__"),
				new MemoryIndexScope(MemoryIndexScopeType.USER, "anonymous")
			),
			"100%_done",
			5
		);

		Assertions.assertTrue(results.isEmpty());
		Assertions.assertNotNull(capturedSql.get());
		Assertions.assertTrue(capturedSql.get().contains("escape '\\'"));
		Assertions.assertFalse(capturedSql.get().contains("escape '\\\\'"));
		Assertions.assertEquals("%100\\%\\_done%", capturedArgs.get()[0]);
	}
}
