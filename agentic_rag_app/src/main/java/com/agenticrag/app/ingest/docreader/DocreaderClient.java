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

	public DocreaderReadResponse readDocument(DocreaderReadRequest request) {
		DocreaderReadResponse response = webClient.post()
			.uri(properties.getReadPath())
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.retrieve()
			.bodyToMono(DocreaderReadResponse.class)
			.timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
			.block();

		if (response == null) {
			response = new DocreaderReadResponse();
		}
		return response;
	}
}
