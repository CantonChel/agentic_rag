package com.agenticrag.app.ingest.docreader;

import com.agenticrag.app.ingest.config.DocreaderProperties;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class DocreaderClient {
	private final WebClient webClient;
	private final DocreaderProperties properties;

	public DocreaderClient(WebClient docreaderWebClient, DocreaderProperties properties) {
		this.webClient = docreaderWebClient;
		this.properties = properties;
	}

	public DocreaderJobSubmitResponse submitJob(DocreaderJobSubmitRequest request) {
		DocreaderJobSubmitResponse response = webClient.post()
			.uri(properties.getJobsPath())
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.retrieve()
			.bodyToMono(DocreaderJobSubmitResponse.class)
			.timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
			.block();

		if (response == null) {
			response = new DocreaderJobSubmitResponse();
		}
		return response;
	}
}
