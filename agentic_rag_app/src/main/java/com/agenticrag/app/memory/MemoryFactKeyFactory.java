package com.agenticrag.app.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MemoryFactKeyFactory {
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public String build(MemoryFactBucket bucket, String subject, String attribute) {
		String normalizedBucket = bucket != null ? normalize(bucket.getValue()) : "";
		String normalizedSubject = normalize(subject);
		String normalizedAttribute = normalize(attribute);
		return sha256(normalizedBucket + "|" + normalizedSubject + "|" + normalizedAttribute);
	}

	private String normalize(String text) {
		return WHITESPACE.matcher(text == null ? "" : text.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
	}

	private String sha256(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((raw != null ? raw : "").getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte value : bytes) {
				out.append(String.format("%02x", value));
			}
			return out.toString();
		} catch (Exception e) {
			return Integer.toHexString(raw != null ? raw.hashCode() : 0);
		}
	}
}
