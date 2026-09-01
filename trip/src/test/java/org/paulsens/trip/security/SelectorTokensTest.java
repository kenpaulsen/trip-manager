package org.paulsens.trip.security;

import java.util.Optional;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.security.SelectorTokens.Judgment;
import org.paulsens.trip.security.SelectorTokens.Minted;
import org.paulsens.trip.security.SelectorTokens.Parsed;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link SelectorTokens} with a hand-rolled clock: the judgments are pure functions of (row, hash, now), so
 * every window edge is tested without sleeping.
 */
public class SelectorTokensTest {

    private static final long NOW = 1_700_000_000L;

    private static AuthToken row(final String validatorHash, final Long expires) {
        return AuthToken.builder().selector("sel").validatorHash(validatorHash).expires(expires).build();
    }

    @Test
    public void mintedPairsParseBackAndNeverRepeat() {
        final Minted one = SelectorTokens.mint();
        final Minted two = SelectorTokens.mint();
        Assert.assertNotEquals(one.selector(), two.selector());
        Assert.assertNotEquals(one.validator(), two.validator());
        Assert.assertEquals(one.validatorHash(), Digests.sha256Base64(one.validator()));

        final Parsed parsed = SelectorTokens.parse(one.presentable()).orElseThrow();
        Assert.assertEquals(parsed.selector(), one.selector());
        Assert.assertEquals(parsed.validator(), one.validator());
        Assert.assertEquals(parsed.validatorHash(), one.validatorHash());
    }

    @Test
    public void malformedPresentationsAreRejectedBeforeAnyIo() {
        for (final String bad : new String[] {null, "", "no-separator", ":leading", "trailing:", ":"}) {
            Assert.assertTrue(SelectorTokens.parse(bad).isEmpty(), "'" + bad + "' must not parse");
        }
        // The validator half may itself contain the separator; only the FIRST one splits.
        final Optional<Parsed> colonInValidator = SelectorTokens.parse("sel:va:lid");
        Assert.assertEquals(colonInValidator.orElseThrow().validator(), "va:lid");
    }

    @Test
    public void theLiveValidatorIsCurrent() {
        final AuthToken row = row(Digests.sha256Base64("v"), NOW + 60);
        Assert.assertEquals(SelectorTokens.judge(row, Digests.sha256Base64("v"), NOW), Judgment.CURRENT);
    }

    /** Expiry is judged FIRST: an expired row with a wrong validator is expiry, not a theft alarm. */
    @Test
    public void expiryOutranksEveryOtherJudgment() {
        final AuthToken expired = row(Digests.sha256Base64("v"), NOW);
        Assert.assertEquals(SelectorTokens.judge(expired, Digests.sha256Base64("v"), NOW), Judgment.EXPIRED);
        Assert.assertEquals(SelectorTokens.judge(expired, Digests.sha256Base64("wrong"), NOW), Judgment.EXPIRED);
        Assert.assertEquals(SelectorTokens.judge(row(Digests.sha256Base64("v"), null),
                Digests.sha256Base64("v"), NOW), Judgment.EXPIRED, "no expiry stamp means never valid");
    }

    @Test
    public void aWrongValidatorWithNoRotationEvidenceIsTheft() {
        final AuthToken row = row(Digests.sha256Base64("v"), NOW + 60);
        Assert.assertEquals(SelectorTokens.judge(row, Digests.sha256Base64("wrong"), NOW), Judgment.THEFT);
    }

    @Test
    public void thePreRotationValidatorIsGraceInsideTheWindowAndTheftOutsideIt() {
        final AuthToken row = row(Digests.sha256Base64("fresh"), NOW + 600);
        row.setPrevValidatorHash(Digests.sha256Base64("old"));
        row.setRotatedAt(NOW - SelectorTokens.ROTATION_GRACE_SECONDS);

        Assert.assertEquals(SelectorTokens.judge(row, Digests.sha256Base64("old"), NOW), Judgment.GRACE,
                "the window edge is inclusive");
        row.setRotatedAt(NOW - SelectorTokens.ROTATION_GRACE_SECONDS - 1);
        Assert.assertEquals(SelectorTokens.judge(row, Digests.sha256Base64("old"), NOW), Judgment.THEFT,
                "one second past the window the stale validator is evidence again");
    }

    /** Only the exact validator the LAST rotation replaced is graced; anything older is theft. */
    @Test
    public void graceCoversExactlyOneGenerationBack() {
        final AuthToken row = row(Digests.sha256Base64("fresh"), NOW + 600);
        row.setPrevValidatorHash(Digests.sha256Base64("old"));
        row.setRotatedAt(NOW - 1);
        Assert.assertEquals(SelectorTokens.judge(row, Digests.sha256Base64("older"), NOW), Judgment.THEFT);
    }
}
