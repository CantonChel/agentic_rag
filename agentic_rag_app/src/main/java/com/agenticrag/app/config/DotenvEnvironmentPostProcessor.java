package com.agenticrag.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> values = new LinkedHashMap<>();

		String explicitPath = getFirstNonBlank(
			environment.getProperty("dotenv.path"),
			environment.getProperty("DOTENV_PATH")
		);

		if (explicitPath != null) {
			loadDotenvFile(values, Paths.get(explicitPath));
		}

		Path userDir = Paths.get(System.getProperty("user.dir"));
		loadDotenvFile(values, userDir.resolve(".env"));

		Path parent = userDir.getParent();
		if (parent != null) {
			loadDotenvFile(values, parent.resolve(".env"));
		}

		if (values.isEmpty()) {
			return;
		}

		MutablePropertySources propertySources = environment.getPropertySources();
		propertySources.addLast(new MapPropertySource("dotenv", values));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	private static String getFirstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String v : values) {
			if (v != null && !v.trim().isEmpty()) {
				return v.trim();
			}
		}
		return null;
	}

	private static void loadDotenvFile(Map<String, Object> out, Path dotenvPath) {
		if (dotenvPath == null || !Files.isRegularFile(dotenvPath)) {
			return;
		}
		try (BufferedReader reader = Files.newBufferedReader(dotenvPath, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				if (trimmed.startsWith("export ")) {
					trimmed = trimmed.substring("export ".length()).trim();
				}

				int eq = trimmed.indexOf('=');
				if (eq <= 0) {
					continue;
				}

				String key = trimmed.substring(0, eq).trim();
				String value = trimmed.substring(eq + 1).trim();
				if (key.isEmpty()) {
					continue;
				}

				value = stripQuotes(value);
				out.putIfAbsent(key, value);
			}
		} catch (IOException ignored) {
		}
	}

	private static String stripQuotes(String value) {
		if (value == null) {
			return null;
		}
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}
}

