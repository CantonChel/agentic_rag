package com.agenticrag.app.rag.splitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public class JTokkitTokenCounter implements TokenCounter {
	private final Encoding encoding;

	public JTokkitTokenCounter() {
		EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
		this.encoding = registry.getEncoding(EncodingType.O200K_BASE);
	}

	@Override
	public int count(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return encoding.countTokens(text);
	}
}

