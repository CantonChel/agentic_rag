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
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		try {
			Dotenv dotenv = loadDotenv();

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
		} catch (Exception ignored) {
		}
	}

	private Dotenv loadDotenv() {
		try {
			Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
			for (int i = 0; i < 6 && dir != null; i++) {
				Path env = dir.resolve(".env");
				if (Files.exists(env) && Files.isRegularFile(env)) {
					return Dotenv.configure()
						.directory(dir.toString())
						.ignoreIfMissing()
						.load();
				}
				dir = dir.getParent();
			}
		} catch (Exception ignored) {
		}

		return Dotenv.configure()
			.ignoreIfMissing()
			.load();
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
}
