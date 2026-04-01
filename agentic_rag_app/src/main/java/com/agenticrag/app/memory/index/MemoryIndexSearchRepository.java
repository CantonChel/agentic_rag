package com.agenticrag.app.memory.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryIndexSearchRepository {
	private static final Logger log = LoggerFactory.getLogger(MemoryIndexSearchRepository.class);

	private final JdbcTemplate jdbcTemplate;

	public MemoryIndexSearchRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<MemoryIndexSearchCandidate> searchVector(
		List<MemoryIndexScope> scopes,
		MemoryIndexProviderProfile profile,
		List<Double> queryEmbedding,
		int limit
	) {
		if (scopes == null || scopes.isEmpty() || profile == null || queryEmbedding == null || queryEmbedding.isEmpty() || limit <= 0) {
			return new ArrayList<>();
		}
		String vecLiteral = toPgvectorLiteral(queryEmbedding);
		List<Object> params = new ArrayList<>();
		StringBuilder sql = new StringBuilder();
		sql.append("select c.path, c.kind, c.block_id, c.line_start, c.line_end, c.content, ");
		sql.append("       (1.0 / (1.0 + (e.vector_json::vector <-> ?::vector))) as retrieval_score ");
		sql.append("from memory_index_chunks c ");
		sql.append("join memory_index_embedding_cache e ");
		sql.append("  on e.chunk_hash = c.chunk_hash ");
		sql.append(" and e.provider = ? ");
		sql.append(" and e.model = ? ");
		sql.append(" and e.provider_key_fingerprint = ? ");
		sql.append("where ");
		params.add(vecLiteral);
		params.add(profile.getProvider());
		params.add(profile.getModel());
		params.add(profile.getProviderKeyFingerprint());
		appendScopeClause(sql, params, scopes);
		sql.append(" order by e.vector_json::vector <-> ?::vector limit ?");
		params.add(vecLiteral);
		params.add(limit);
		try {
			return mapRows(jdbcTemplate.queryForList(sql.toString(), params.toArray()));
		} catch (Exception e) {
			log.warn("event=memory_search_vector_error type={} message={}", e.getClass().getSimpleName(), e.getMessage());
			return new ArrayList<>();
		}
	}

	public List<MemoryIndexSearchCandidate> searchLexical(List<MemoryIndexScope> scopes, String query, int limit) {
		if (scopes == null || scopes.isEmpty() || query == null || query.trim().isEmpty() || limit <= 0) {
			return new ArrayList<>();
		}
		String normalized = query.trim();
		String likePattern = "%" + escapeLike(normalized) + "%";
		List<Object> params = new ArrayList<>();
		StringBuilder sql = new StringBuilder();
		sql.append("select c.path, c.kind, c.block_id, c.line_start, c.line_end, c.content, ");
		sql.append("       ((case when c.content ilike ? escape '\\\\' then 2.0 else 0.0 end) ");
		sql.append("      + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) ");
		sql.append("      + similarity(c.content, ?)) as retrieval_score ");
		sql.append("from memory_index_chunks c where ");
		params.add(likePattern);
		params.add(normalized);
		params.add(normalized);
		appendScopeClause(sql, params, scopes);
		sql.append(" and (c.content ilike ? escape '\\\\' ");
		sql.append("  or to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?) ");
		sql.append("  or c.content % ?) ");
		sql.append("order by ");
		sql.append("  (case when c.content ilike ? escape '\\\\' then 2.0 else 0.0 end) ");
		sql.append("  + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) ");
		sql.append("  + similarity(c.content, ?) desc limit ?");
		params.add(likePattern);
		params.add(normalized);
		params.add(normalized);
		params.add(likePattern);
		params.add(normalized);
		params.add(normalized);
		params.add(limit);
		try {
			return mapRows(jdbcTemplate.queryForList(sql.toString(), params.toArray()));
		} catch (Exception e) {
			log.warn("event=memory_search_lexical_error type={} message={}", e.getClass().getSimpleName(), e.getMessage());
			return new ArrayList<>();
		}
	}

	private void appendScopeClause(StringBuilder sql, List<Object> params, List<MemoryIndexScope> scopes) {
		sql.append("(");
		for (int i = 0; i < scopes.size(); i++) {
			if (i > 0) {
				sql.append(" or ");
			}
			sql.append("(c.scope_type = ? and c.scope_id = ?)");
			params.add(scopes.get(i).getTypeValue());
			params.add(scopes.get(i).getId());
		}
		sql.append(")");
	}

	private List<MemoryIndexSearchCandidate> mapRows(List<Map<String, Object>> rows) {
		List<MemoryIndexSearchCandidate> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (Map<String, Object> row : rows) {
			if (row == null) {
				continue;
			}
			out.add(new MemoryIndexSearchCandidate(
				stringValue(row.get("path")),
				stringValue(row.get("kind")),
				stringValue(row.get("block_id")),
				intValue(row.get("line_start")),
				intValue(row.get("line_end")),
				stringValue(row.get("content")),
				doubleValue(row.get("retrieval_score"))
			));
		}
		return out;
	}

	private String toPgvectorLiteral(List<Double> vector) {
		StringBuilder out = new StringBuilder();
		out.append('[');
		for (int i = 0; i < vector.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append(vector.get(i) != null ? vector.get(i) : 0.0);
		}
		out.append(']');
		return out.toString();
	}

	private String escapeLike(String input) {
		return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private String stringValue(Object value) {
		return value != null ? String.valueOf(value) : null;
	}

	private int intValue(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value == null) {
			return 0;
		}
		return Integer.parseInt(String.valueOf(value));
	}

	private double doubleValue(Object value) {
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		if (value == null) {
			return 0.0;
		}
		return Double.parseDouble(String.valueOf(value));
	}
}
