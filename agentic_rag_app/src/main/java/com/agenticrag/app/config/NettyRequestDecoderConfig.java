package com.agenticrag.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyRequestDecoderConfig {
	@Bean
	public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyRequestDecoderCustomizer(
		@Value("${server.netty.max-initial-line-length:16384}") int maxInitialLineLength,
		@Value("${server.netty.max-header-size:16384}") int maxHeaderSize
	) {
		return factory -> factory.addServerCustomizers(httpServer -> httpServer.httpRequestDecoder(spec ->
			spec.maxInitialLineLength(Math.max(4096, maxInitialLineLength))
				.maxHeaderSize(Math.max(4096, maxHeaderSize))
		));
	}
}
