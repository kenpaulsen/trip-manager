package org.paulsens.trip.model;

import org.paulsens.trip.model.chat.ChatMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Ordering of the whole chat log rests on 13-digit zero-padded millis string order. */
public class ChatMessageIdTest {

    @Test
    public void paddingIsExactly13Digits() {
        Assert.assertEquals(ChatMessage.Id.of(0).getValue().length(), 13);
        Assert.assertEquals(ChatMessage.Id.of(999).getValue(), "0000000000999");
        Assert.assertEquals(ChatMessage.Id.of(1_700_000_000_000L).getValue(), "1700000000000");
    }

    @Test
    public void stringOrderMatchesNumericOrderAcrossBoundary() {
        // Unpadded, "999" would sort AFTER "1000000000000". Padding must keep numeric order.
        final ChatMessage.Id small = ChatMessage.Id.of(999L);
        final ChatMessage.Id large = ChatMessage.Id.of(1_000_000_000_000L);
        Assert.assertTrue(small.getValue().compareTo(large.getValue()) < 0);
        Assert.assertTrue(small.compareTo(large) < 0);
    }

    @Test
    public void getEpochMilliSurvivesNullAndGarbage() {
        Assert.assertEquals(ChatMessage.Id.from(null).getEpochMilli(), 0L);
        Assert.assertEquals(ChatMessage.Id.from("").getEpochMilli(), 0L);
        Assert.assertEquals(ChatMessage.Id.from("not-a-number").getEpochMilli(), 0L);
        Assert.assertEquals(ChatMessage.Id.of(42).getEpochMilli(), 42L);
    }

    @Test
    public void nextIsPlusOneMs() {
        final ChatMessage.Id id = ChatMessage.Id.of(1000L);
        Assert.assertEquals(id.next().getEpochMilli(), 1001L);
        Assert.assertEquals(id.next().getValue(), "0000000001001");
    }
}
