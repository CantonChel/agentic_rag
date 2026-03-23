package com.agenticrag.app.ingest.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ingest.file-storage.backend", havingValue = "minio")
public class MinioClientConfig {
	@Bean
	public MinioClient minioClient(MinioStorageProperties properties) {
		return MinioClient.builder()
			.endpoint(properties.getEndpoint())
			.credentials(properties.getAccessKey(), properties.getSecretKey())
			.build();
	}
}
