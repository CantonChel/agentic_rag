package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.DocreaderProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CallbackSignatureVerifier {
	private final DocreaderProperties properties;

	public CallbackSignatureVerifier(DocreaderProperties properties) {
		this.properties = properties;
	}

	public boolean verify(String timestampHeader, String signatureHeader, String payload) {
		String secret = properties.getCallbackSecret();
		if (secret == null || secret.trim().isEmpty()) {
			return true;
		}
		if (timestampHeader == null || timestampHeader.trim().isEmpty() || signatureHeader == null || signatureHeader.trim().isEmpty()) {
			return false;
		}

		Long timestamp = parseTimestamp(timestampHeader.trim());
		if (timestamp == null) {
			return false;
		}
		long now = Instant.now().getEpochSecond();
		if (Math.abs(now - timestamp) > properties.getCallbackMaxSkewSeconds()) {
			return false;
		}

		String body = payload != null ? payload : "";
		String content = timestamp + "." + body;
		String expected = hmacSha256Hex(secret, content);
		return constantTimeEquals(expected, signatureHeader.trim().toLowerCase());
	}

	private Long parseTimestamp(String s) {
		try {
			long val = Long.parseLong(s);
			if (val > 1000000000000L) {
				return val / 1000L;
			}
			return val;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private String hmacSha256Hex(String secret, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			mac.init(key);
			byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : out) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException("failed to verify hmac", e);
		}
	}

	private boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
