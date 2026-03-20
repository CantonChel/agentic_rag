package com.agenticrag.app.api;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug/dotenv")
public class DotenvDebugController {
	private final Environment environment;

	public DotenvDebugController(Environment environment) {
		this.environment = environment;
	}

	@GetMapping
	public DotenvView get() {
		String loaded = environment.getProperty("dotenv.loaded", "false");
		String path = environment.getProperty("dotenv.path", "");
		String keyCount = environment.getProperty("dotenv.keyCount", "0");
		String userDir = System.getProperty("user.dir", "");
		return new DotenvView(
			Boolean.parseBoolean(loaded),
			path,
			parseInt(keyCount),
			userDir
		);
	}

	private int parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (Exception e) {
			return 0;
		}
	}

	public static class DotenvView {
		private final boolean loaded;
		private final String path;
		private final int keyCount;
		private final String userDir;

		public DotenvView(boolean loaded, String path, int keyCount, String userDir) {
			this.loaded = loaded;
			this.path = path;
			this.keyCount = keyCount;
			this.userDir = userDir;
		}

		public boolean isLoaded() {
			return loaded;
		}

		public String getPath() {
			return path;
		}

		public int getKeyCount() {
			return keyCount;
		}

		public String getUserDir() {
			return userDir;
		}
	}
}
