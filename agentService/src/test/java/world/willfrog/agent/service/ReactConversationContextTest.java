package world.willfrog.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReactConversationContext 单元测试。
 */
class ReactConversationContextTest {

    @Test
    void newContext_shouldBeEmpty() {
        ReactConversationContext ctx = new ReactConversationContext();
        assertEquals(0, ctx.size());
        assertEquals(0, ctx.conversationSize());
        assertTrue(ctx.getMessages().isEmpty());
    }

    @Test
    void setSystemMessage_shouldBeFirstMessage() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.setSystemMessage("你是金融助手。");
        assertEquals(1, ctx.size());
        assertEquals(0, ctx.conversationSize());
        assertInstanceOf(SystemMessage.class, ctx.getMessages().get(0));
    }

    @Test
    void setSystemMessage_shouldReplaceExisting() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.setSystemMessage("旧指令");
        ctx.setSystemMessage("新指令");
        assertEquals(1, ctx.size());
        SystemMessage sm = (SystemMessage) ctx.getMessages().get(0);
        assertEquals("新指令", sm.text());
    }

    @Test
    void addUserAndAssistantMessages_shouldAccumulate() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.setSystemMessage("系统指令");
        ctx.addUserMessage("用户问题1");
        ctx.addAssistantMessage("回复1");
        ctx.addUserMessage("用户问题2");

        assertEquals(4, ctx.size());
        assertEquals(3, ctx.conversationSize());
        List<ChatMessage> msgs = ctx.getMessages();
        assertInstanceOf(SystemMessage.class, msgs.get(0));
        assertInstanceOf(UserMessage.class, msgs.get(1));
        assertInstanceOf(AiMessage.class, msgs.get(2));
        assertInstanceOf(UserMessage.class, msgs.get(3));
    }

    @Test
    void getMessages_shouldReturnUnmodifiableList() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.addUserMessage("test");
        List<ChatMessage> msgs = ctx.getMessages();
        try {
            msgs.add(new UserMessage("hack"));
        } catch (UnsupportedOperationException e) {
            // expected
        }
        assertEquals(1, ctx.size(), "原始列表不应被外部修改");
    }

    @Test
    void trimIfNeeded_shouldRemoveOldestNonSystemMessages() {
        ReactConversationContext ctx = new ReactConversationContext(4);
        ctx.setSystemMessage("系统");
        ctx.addUserMessage("U1");
        ctx.addAssistantMessage("A1");
        ctx.addUserMessage("U2");
        ctx.addAssistantMessage("A2");

        // 4 conversation messages, at limit
        assertEquals(5, ctx.size());
        assertEquals(4, ctx.conversationSize());

        // Add one more, should trim the oldest non-system message
        ctx.addUserMessage("U3");
        assertEquals(5, ctx.size());
        assertEquals(4, ctx.conversationSize());

        // System message should still be first
        assertInstanceOf(SystemMessage.class, ctx.getMessages().get(0));

        // Oldest message (U1) should have been removed; A1 is now first conversation message
        assertInstanceOf(AiMessage.class, ctx.getMessages().get(1));
    }

    @Test
    void trimIfNeeded_withoutSystemMessage() {
        ReactConversationContext ctx = new ReactConversationContext(2);
        ctx.addUserMessage("U1");
        ctx.addAssistantMessage("A1");

        // At limit with 2 messages
        assertEquals(2, ctx.size());
        assertEquals(2, ctx.conversationSize());

        // Add one more, should trim U1
        ctx.addUserMessage("U2");
        assertEquals(2, ctx.size());
        assertInstanceOf(AiMessage.class, ctx.getMessages().get(0));
        assertInstanceOf(UserMessage.class, ctx.getMessages().get(1));
    }

    @Test
    void clear_shouldRemoveAllMessages() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.setSystemMessage("sys");
        ctx.addUserMessage("u");
        ctx.addAssistantMessage("a");
        ctx.clear();
        assertEquals(0, ctx.size());
        assertTrue(ctx.getMessages().isEmpty());
    }

    @Test
    void blankMessages_shouldBeIgnored() {
        ReactConversationContext ctx = new ReactConversationContext();
        ctx.setSystemMessage("");
        ctx.setSystemMessage(null);
        ctx.addUserMessage("");
        ctx.addUserMessage(null);
        ctx.addAssistantMessage("");
        ctx.addAssistantMessage(null);
        assertEquals(0, ctx.size());
    }

    @Test
    void systemMessageStaysDuringTrim() {
        ReactConversationContext ctx = new ReactConversationContext(2);
        ctx.setSystemMessage("常驻系统指令");
        // Fill beyond max
        ctx.addUserMessage("U1");
        ctx.addAssistantMessage("A1");
        ctx.addUserMessage("U2");
        ctx.addAssistantMessage("A2");
        ctx.addUserMessage("U3");

        // System message must always be at index 0
        assertInstanceOf(SystemMessage.class, ctx.getMessages().get(0));
        assertEquals("常驻系统指令", ((SystemMessage) ctx.getMessages().get(0)).text());
        // Non-system messages should be exactly maxMessages
        assertEquals(2, ctx.conversationSize());
    }

    @Test
    void defaultMaxMessages_shouldBeDefault() {
        ReactConversationContext ctx = new ReactConversationContext();
        // Add DEFAULT_MAX_MESSAGES + system + extra
        ctx.setSystemMessage("sys");
        for (int i = 0; i < ReactConversationContext.DEFAULT_MAX_MESSAGES + 5; i++) {
            ctx.addUserMessage("msg" + i);
        }
        assertEquals(ReactConversationContext.DEFAULT_MAX_MESSAGES, ctx.conversationSize());
    }
}
