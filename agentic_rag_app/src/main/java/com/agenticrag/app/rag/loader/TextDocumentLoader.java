package com.agenticrag.app.rag.loader;

import com.agenticrag.app.rag.model.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class TextDocumentLoader implements DocumentLoader {
	private final TextDocumentLoaderProperties properties;

	public TextDocumentLoader(TextDocumentLoaderProperties properties) {
		this.properties = properties;
	}

	@Override
	public List<Document> load() {
		String rootDir = properties != null ? properties.getRootDir() : null;
		if (rootDir == null || rootDir.trim().isEmpty()) {
			return new ArrayList<>();
		}

		Path root = Paths.get(rootDir.trim());
		if (!Files.exists(root) || !Files.isDirectory(root)) {
			return new ArrayList<>();
		}

		List<Document> docs = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(root)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> {
					String name = p.getFileName().toString().toLowerCase();
					return name.endsWith(".txt") || name.endsWith(".md");
				})
				.forEach(p -> {
					try {
						String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
						Map<String, Object> metadata = buildMetadata(root, p);
						docs.add(new Document((String) metadata.get("source"), content, metadata));
					} catch (Exception ignored) {
					}
				});
		} catch (IOException ignored) {
			return new ArrayList<>();
		}

		return docs;
	}

	private Map<String, Object> buildMetadata(Path root, Path file) throws IOException {
		Map<String, Object> metadata = new HashMap<>();
		String relative = root.relativize(file).toString();
		metadata.put("source", relative);
		metadata.put("path", file.toAbsolutePath().toString());

		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
		if (attrs.creationTime() != null) {
			metadata.put("creation_date", attrs.creationTime().toString());
		}
		if (attrs.lastModifiedTime() != null) {
			metadata.put("last_modified", attrs.lastModifiedTime().toString());
		}
		return metadata;
	}
}

