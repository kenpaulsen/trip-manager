package org.paulsens.trip.security;

import org.testng.Assert;
import org.testng.annotations.Test;

public class PasswordPolicyTest {

    @Test
    public void acceptsEightCharactersWithALetterAndADigit() {
        Assert.assertTrue(PasswordPolicy.accepts("abcdefg1"));
        Assert.assertTrue(PasswordPolicy.accepts("1234567a"));
        Assert.assertTrue(PasswordPolicy.accepts("correct horse 9"), "spaces and length beyond the minimum are fine");
        Assert.assertTrue(PasswordPolicy.accepts("\u00e9t\u00e9-2026-pass"), "any letter counts, not only ASCII");
        Assert.assertNull(PasswordPolicy.problem("abcdefg1"));
    }

    @Test
    public void refusesShortOrOneClassPasswords() {
        Assert.assertFalse(PasswordPolicy.accepts("abcdef1"), "seven characters");
        Assert.assertFalse(PasswordPolicy.accepts("abcdefgh"), "no digit");
        Assert.assertFalse(PasswordPolicy.accepts("12345678"), "no letter");
        Assert.assertFalse(PasswordPolicy.accepts("!@#$%^&*()"), "neither");
        Assert.assertFalse(PasswordPolicy.accepts(null));
        Assert.assertEquals(PasswordPolicy.problem("abcdefgh"), PasswordPolicy.RULE);
    }

    @Test
    public void aMissingPasswordIsItsOwnMessage() {
        Assert.assertEquals(PasswordPolicy.problem(null), "A password is required.");
        Assert.assertEquals(PasswordPolicy.problem("   "), "A password is required.");
    }

    /** Length is counted in code points so an emoji-heavy password is not over-counted by UTF-16 pairs. */
    @Test
    public void lengthCountsCodePoints() {
        Assert.assertFalse(PasswordPolicy.accepts("a1\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"), "5 code points");
    }
}
