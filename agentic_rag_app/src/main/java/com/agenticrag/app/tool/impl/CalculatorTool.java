package com.agenticrag.app.tool.impl;

import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class CalculatorTool implements Tool {
	private final ObjectMapper objectMapper;

	public CalculatorTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "calculator";
	}

	@Override
	public String description() {
		return "Perform a simple arithmetic operation on two numbers.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");

		ObjectNode op = properties.putObject("op");
		op.put("type", "string");
		op.putArray("enum").add("add").add("sub").add("mul").add("div");

		ObjectNode a = properties.putObject("a");
		a.put("type", "number");
		ObjectNode b = properties.putObject("b");
		b.put("type", "number");

		root.putArray("required").add("op").add("a").add("b");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		if (arguments == null) {
			return Mono.just(ToolResult.error("Missing arguments"));
		}

		String op = arguments.hasNonNull("op") ? arguments.get("op").asText() : null;
		if (op == null) {
			return Mono.just(ToolResult.error("Missing op"));
		}

		if (!arguments.hasNonNull("a") || !arguments.hasNonNull("b")) {
			return Mono.just(ToolResult.error("Missing a or b"));
		}

		BigDecimal a = toBigDecimal(arguments.get("a"));
		BigDecimal b = toBigDecimal(arguments.get("b"));

		if (a == null || b == null) {
			return Mono.just(ToolResult.error("Invalid a or b"));
		}

		BigDecimal result;
		if ("add".equals(op)) {
			result = a.add(b);
		} else if ("sub".equals(op)) {
			result = a.subtract(b);
		} else if ("mul".equals(op)) {
			result = a.multiply(b);
		} else if ("div".equals(op)) {
			if (b.compareTo(BigDecimal.ZERO) == 0) {
				return Mono.just(ToolResult.error("Division by zero"));
			}
			result = a.divide(b, 16, RoundingMode.HALF_UP).stripTrailingZeros();
		} else {
			return Mono.just(ToolResult.error("Unsupported op: " + op));
		}

		return Mono.just(ToolResult.ok(result.toPlainString()));
	}

	private BigDecimal toBigDecimal(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			return node.decimalValue();
		}
		if (node.isTextual()) {
			try {
				return new BigDecimal(node.asText().trim());
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}
}

