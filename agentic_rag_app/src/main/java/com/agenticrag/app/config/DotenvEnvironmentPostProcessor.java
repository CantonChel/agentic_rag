package com.agenticrag.app.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
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
				Path env = dir.resolve(".env");
				if (Files.exists(env) && Files.isRegularFile(env)) {
					return new DotenvLoadResult(true, env, Dotenv.configure()
						.directory(dir.toString())
						.ignoreIfMissing()
						.load());
				}
			}

			Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
			for (int i = 0; i < MAX_SEARCH_DEPTH && dir != null; i++) {
				Path env = dir.resolve(".env");
				if (Files.exists(env) && Files.isRegularFile(env)) {
					return new DotenvLoadResult(true, env, Dotenv.configure()
						.directory(dir.toString())
						.ignoreIfMissing()
						.load());
				}
				dir = dir.getParent();
			}
		} catch (Exception ignored) {
		}

		return new DotenvLoadResult(false, null, Dotenv.configure()
			.ignoreIfMissing()
			.load());
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
