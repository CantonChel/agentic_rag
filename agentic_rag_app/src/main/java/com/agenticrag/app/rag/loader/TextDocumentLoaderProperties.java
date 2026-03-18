package com.agenticrag.app.rag.loader;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.loader.text")
public class TextDocumentLoaderProperties {
	private String rootDir = "";

	public String getRootDir() {
		return rootDir;
	}

	public void setRootDir(String rootDir) {
		this.rootDir = rootDir;
	}
}

