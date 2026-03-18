package com.agenticrag.app.tool;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ToolAutoRegistrar {
	public ToolAutoRegistrar(ToolRouter toolRouter, List<Tool> tools) {
		tools.forEach(toolRouter::register);
	}
}

