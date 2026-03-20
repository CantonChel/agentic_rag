package com.agenticrag.app.ingest.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class IngestConfig {
	@Bean(name = "docParseWorkerExecutor")
	public Executor docParseWorkerExecutor(IngestAsyncProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("doc-parse-worker-");
		executor.setCorePoolSize(properties.getWorkerCorePoolSize());
		executor.setMaxPoolSize(properties.getWorkerMaxPoolSize());
		executor.setQueueCapacity(properties.getWorkerQueueCapacity());
		executor.initialize();
		return executor;
	}

	@Bean
	public WebClient docreaderWebClient(DocreaderProperties properties) {
		HttpClient client = HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMillis())
			.doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(properties.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)));
		return WebClient.builder()
			.baseUrl(properties.getBaseUrl())
			.clientConnector(new ReactorClientHttpConnector(client))
			.build();
	}
}
