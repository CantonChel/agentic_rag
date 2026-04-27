package com.agenticrag.app.loadtest;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest")
public class LoadTestMetricsController {

	private final LoadTestMetricsCollector metricsCollector;

	public LoadTestMetricsController(LoadTestMetricsCollector metricsCollector) {
		this.metricsCollector = metricsCollector;
	}

	@GetMapping("/metrics")
	public Map<String, Object> metrics() {
		return metricsCollector.snapshot();
	}

	@PostMapping("/metrics/reset")
	public Map<String, Object> reset() {
		metricsCollector.reset();
		return metricsCollector.snapshot();
	}
}
