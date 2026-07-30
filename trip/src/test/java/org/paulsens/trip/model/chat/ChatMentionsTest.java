package org.paulsens.trip.model.chat;

import java.util.List;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The mention syntax is pinned by tests from the day it ships, because it is the one thing in this feature that
 * cannot be changed later: the tokens are embedded in message bodies, and bodies are immutable history.
 */
public class ChatMentionsTest {

    @Test
    public void extractsIdsInOrderWithoutDuplicates() {
        final String body = "@{alice} and @{bob}, also @{alice} again";
        Assert.assertEquals(ChatMentions.extract(body),
                List.of(Person.Id.from("alice"), Person.Id.from("bob")));
    }

    @Test
    public void tokenRoundTripsThroughExtract() {
        final Person.Id id = Person.Id.from("7f3c1a44-0000-4a1b-9c2d-abcdefabcdef");
        Assert.assertEquals(ChatMentions.extract("hi " + ChatMentions.token(id)), List.of(id));
    }

    @Test
    public void prosePlainEmailAndBareAtDoNotMatch() {
        // The pattern has to be strict: an ordinary message must never accidentally mention someone.
        Assert.assertEquals(ChatMentions.extract("email me at bob@example.com"), List.of());
        Assert.assertEquals(ChatMentions.extract("meet @ the blue cross at 7"), List.of());
        Assert.assertEquals(ChatMentions.extract("@notbraced"), List.of());
        Assert.assertEquals(ChatMentions.extract("@{}"), List.of());
        Assert.assertEquals(ChatMentions.extract("@{has spaces}"), List.of());
    }

    @Test
    public void nullAndEmptyAreSafe() {
        Assert.assertEquals(ChatMentions.extract(null), List.of());
        Assert.assertEquals(ChatMentions.extract(""), List.of());
        Assert.assertEquals(ChatMentions.token(null), "");
        Assert.assertFalse(ChatMentions.mentions(null, Person.Id.from("a")));
        Assert.assertFalse(ChatMentions.mentions("@{a}", null));
    }

    @Test
    public void mentionsAnswersMembership() {
        Assert.assertTrue(ChatMentions.mentions("ping @{carol}", Person.Id.from("carol")));
        Assert.assertFalse(ChatMentions.mentions("ping @{carol}", Person.Id.from("dave")));
    }

    @Test
    public void anOverlongIdIsNotAMention() {
        // Bounded so a pathological body cannot make the matcher do unbounded work.
        Assert.assertEquals(ChatMentions.extract("@{" + "x".repeat(200) + "}"), List.of());
    }
}
