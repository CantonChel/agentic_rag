package com.agenticrag.app.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
	private static final int MAX_SEARCH_DEPTH = 10;
	private static final String DOTENV_DIR_ENV = "DOTENV_DIR";
	private static final String DOTENV_DIR_PROP = "dotenv.dir";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		try {
			DotenvLoadResult result = loadDotenv();
			Dotenv dotenv = result.dotenv;

			Map<String, Object> props = new HashMap<>();
			for (DotenvEntry e : dotenv.entries()) {
				if (e == null || e.getKey() == null) {
					continue;
				}
				props.put(e.getKey(), e.getValue());
			}
			if (!props.isEmpty()) {
				environment.getPropertySources().addFirst(new MapPropertySource("dotenv", props));
			}
			Map<String, Object> meta = new HashMap<>();
			meta.put("dotenv.loaded", result.loaded);
			meta.put("dotenv.path", result.path == null ? "" : result.path.toString());
			meta.put("dotenv.keyCount", props.size());
			environment.getPropertySources().addFirst(new MapPropertySource("dotenv-meta", meta));
		} catch (Exception ignored) {
		}
	}

	private DotenvLoadResult loadDotenv() {
		try {
			String configuredDir = System.getProperty(DOTENV_DIR_PROP);
			if (configuredDir == null || configuredDir.trim().isEmpty()) {
				configuredDir = System.getenv(DOTENV_DIR_ENV);
			}
			if (configuredDir != null && !configuredDir.trim().isEmpty()) {
				Path dir = Paths.get(configuredDir).toAbsolutePath().normalize();
				DotenvLoadResult fromConfigured = loadFromDir(dir);
				if (fromConfigured.loaded) {
					return fromConfigured;
				}
			}

			Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
			DotenvLoadResult fromUserDir = findAndLoad(userDir);
			if (fromUserDir.loaded) {
				return fromUserDir;
			}

			Path codeSourceDir = resolveCodeSourceDir();
			if (codeSourceDir != null) {
				DotenvLoadResult fromCodeSource = findAndLoad(codeSourceDir);
				if (fromCodeSource.loaded) {
					return fromCodeSource;
				}
			}
		} catch (Exception ignored) {
		}

		return new DotenvLoadResult(false, null, Dotenv.configure()
			.ignoreIfMissing()
			.load());
	}

	private DotenvLoadResult findAndLoad(Path start) {
		Path dir = start;
		for (int i = 0; i < MAX_SEARCH_DEPTH && dir != null; i++) {
			DotenvLoadResult result = loadFromDir(dir);
			if (result.loaded) {
				return result;
			}
			dir = dir.getParent();
		}
		return new DotenvLoadResult(false, null, Dotenv.configure().ignoreIfMissing().load());
	}

	private DotenvLoadResult loadFromDir(Path dir) {
		if (dir == null) {
			return new DotenvLoadResult(false, null, Dotenv.configure().ignoreIfMissing().load());
		}
		Path env = dir.resolve(".env");
		if (Files.exists(env) && Files.isRegularFile(env)) {
			return new DotenvLoadResult(true, env, Dotenv.configure()
				.directory(dir.toString())
				.ignoreIfMissing()
				.load());
		}
		return new DotenvLoadResult(false, null, Dotenv.configure().ignoreIfMissing().load());
	}

	private Path resolveCodeSourceDir() {
		try {
			URI uri = DotenvEnvironmentPostProcessor.class.getProtectionDomain()
				.getCodeSource()
				.getLocation()
				.toURI();
			Path path = Paths.get(uri).toAbsolutePath().normalize();
			if (Files.isDirectory(path)) {
				return path;
			}
			return path.getParent();
		} catch (Exception ignored) {
			return null;
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	private static class DotenvLoadResult {
		private final boolean loaded;
		private final Path path;
		private final Dotenv dotenv;

		private DotenvLoadResult(boolean loaded, Path path, Dotenv dotenv) {
			this.loaded = loaded;
			this.path = path;
			this.dotenv = dotenv;
		}
	}
}
