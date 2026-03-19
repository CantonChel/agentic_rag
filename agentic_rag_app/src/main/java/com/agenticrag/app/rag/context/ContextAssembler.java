package com.agenticrag.app.rag.context;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContextAssembler {
	public String assemble(List<TextChunk> chunks) {
		StringBuilder out = new StringBuilder();
		out.append("<context>\n");
		if (chunks != null) {
			int idx = 1;
			for (TextChunk c : chunks) {
				if (c == null) {
					continue;
				}
				Object source = c.getMetadata() != null ? c.getMetadata().get("source") : null;
				out.append("[引用 ").append(idx).append("] 来源: ").append(source != null ? String.valueOf(source) : "").append("\n");
				out.append(c.getText() != null ? c.getText() : "").append("\n\n");
				idx++;
			}
		}
		out.append("</context>\n");
		return out.toString();
	}
}

