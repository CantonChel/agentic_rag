package com.agenticrag.app.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ToolArgumentValidator {
	private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

	public ValidationResult validate(JsonNode schemaNode, JsonNode arguments) {
		if (schemaNode == null) {
			return ValidationResult.ok();
		}
		if (arguments == null) {
			arguments = com.fasterxml.jackson.databind.node.NullNode.getInstance();
		}

		try {
			JsonSchema schema = schemaFactory.getSchema(schemaNode);
			Set<ValidationMessage> errors = schema.validate(arguments);
			if (errors == null || errors.isEmpty()) {
				return ValidationResult.ok();
			}
			List<String> msgs = new ArrayList<>();
			for (ValidationMessage e : errors) {
				if (e != null && e.getMessage() != null) {
					msgs.add(e.getMessage());
				}
			}
			return ValidationResult.error(msgs);
		} catch (Exception e) {
			return ValidationResult.error(java.util.Collections.singletonList("Invalid schema or arguments: " + e.getMessage()));
		}
	}

	public static class ValidationResult {
		private final boolean ok;
		private final List<String> errors;

		private ValidationResult(boolean ok, List<String> errors) {
			this.ok = ok;
			this.errors = errors;
		}

		public static ValidationResult ok() {
			return new ValidationResult(true, java.util.Collections.emptyList());
		}

		public static ValidationResult error(List<String> errors) {
			return new ValidationResult(false, errors != null ? errors : java.util.Collections.emptyList());
		}

		public boolean isOk() {
			return ok;
		}

		public List<String> getErrors() {
			return errors;
		}
	}
}

