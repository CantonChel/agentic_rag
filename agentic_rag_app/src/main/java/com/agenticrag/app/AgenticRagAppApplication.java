package com.agenticrag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgenticRagAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgenticRagAppApplication.class, args);
	}

}
