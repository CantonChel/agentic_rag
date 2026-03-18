package com.agenticrag.app.chat.memory;

import com.agenticrag.app.chat.message.UserMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InMemoryWindowMemoryTest {
	@Test
	void trimsToMaxMessages() {
		WindowMemoryProperties props = new WindowMemoryProperties();
		props.setMaxMessages(3);
		InMemoryWindowMemory memory = new InMemoryWindowMemory(props);

		memory.append("s1", new UserMessage("m1"));
		memory.append("s1", new UserMessage("m2"));
		memory.append("s1", new UserMessage("m3"));
		memory.append("s1", new UserMessage("m4"));
		memory.append("s1", new UserMessage("m5"));

		Assertions.assertEquals(3, memory.getMessages("s1").size());
		Assertions.assertEquals("m3", memory.getMessages("s1").get(0).getContent());
		Assertions.assertEquals("m5", memory.getMessages("s1").get(2).getContent());
	}

	@Test
	void isolatesSessions() {
		WindowMemoryProperties props = new WindowMemoryProperties();
		props.setMaxMessages(2);
		InMemoryWindowMemory memory = new InMemoryWindowMemory(props);

		memory.append("a", new UserMessage("a1"));
		memory.append("b", new UserMessage("b1"));
		memory.append("a", new UserMessage("a2"));
		memory.append("a", new UserMessage("a3"));

		Assertions.assertEquals(2, memory.getMessages("a").size());
		Assertions.assertEquals(1, memory.getMessages("b").size());
		Assertions.assertEquals("a2", memory.getMessages("a").get(0).getContent());
		Assertions.assertEquals("b1", memory.getMessages("b").get(0).getContent());
	}
}

