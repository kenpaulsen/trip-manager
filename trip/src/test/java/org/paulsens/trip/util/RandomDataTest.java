package org.paulsens.trip.util;

import java.util.HashSet;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The secure generators in {@link RandomData}. Randomness itself is not assertable; what is: the alphabet,
 * the length, and that the output covers its range rather than collapsing onto a few values.
 */
public class RandomDataTest {

    @Test
    public void secureDigitsAreDigitsOfTheRequestedLength() {
        for (final int len : new int[] {1, 6, 10}) {
            final String code = RandomData.genSecureDigits(len);
            Assert.assertEquals(code.length(), len);
            Assert.assertTrue(code.chars().allMatch(Character::isDigit), "not all digits: " + code);
        }
    }

    @Test
    public void secureDigitsCoverAllTenDigits() {
        final Set<Character> seen = new HashSet<>();
        for (int draw = 0; draw < 200; draw++) {
            for (final char digit : RandomData.genSecureDigits(6).toCharArray()) {
                seen.add(digit);
            }
        }
        Assert.assertEquals(seen.size(), 10, "1200 secure digits should include every digit: " + seen);
    }

    @Test
    public void secureTokensAreUrlSafeAndDistinct() {
        final Set<String> seen = new HashSet<>();
        for (int draw = 0; draw < 100; draw++) {
            final String token = RandomData.genSecureToken(32);
            Assert.assertTrue(token.matches("[A-Za-z0-9_-]+"), "not base64url: " + token);
            Assert.assertTrue(seen.add(token), "a 32-byte token repeated -- the generator is broken");
        }
        // 32 bytes -> 43 unpadded base64 chars: the token really carries the entropy asked for.
        Assert.assertEquals(RandomData.genSecureToken(32).length(), 43);
    }

    @Test
    public void securePassCharsStayInThePasswordAlphabet() {
        final String pass = RandomData.genSecurePassChars(24);
        Assert.assertEquals(pass.length(), 24);
        final String alphabet = new String(RandomData.PASS_CHARS);
        for (final char ch : pass.toCharArray()) {
            Assert.assertTrue(alphabet.indexOf(ch) >= 0, "'" + ch + "' is not a password character");
        }
    }
}
