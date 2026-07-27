package org.paulsens.trip.action;

import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The filter-parsing and CSV rules behind the admin audit page.
 *
 * <p>Both of the interesting cases here fail SILENTLY in production if they regress: a mis-parsed filter shows
 * the wrong rows rather than erroring, and a badly escaped CSV opens in a spreadsheet with columns quietly
 * shifted.
 */
public class AuditViewCommandsTest {

    private final AuditViewCommands view = new AuditViewCommands();

    @Test
    public void blankFilterMeansNoFilter() {
        // EL hands an unset dropdown "" rather than null.
        Assert.assertNull(AuditViewCommands.parseAction(""));
        Assert.assertNull(AuditViewCommands.parseAction("   "));
        Assert.assertNull(AuditViewCommands.parseAction(null));
        Assert.assertNull(AuditViewCommands.parseOutcome(""));
    }

    @Test
    public void unrecognisedFilterMeansNoFilter() {
        // AuditAction.from() maps anything unknown to UNKNOWN. As a FILTER that would mean "show only the
        // records we could not classify" -- the opposite of what a stale bookmark or a renamed action intends.
        Assert.assertNull(AuditViewCommands.parseAction("NoSuchAction"),
                "An unrecognised filter must widen to 'any', not narrow to UNKNOWN");
        Assert.assertNull(AuditViewCommands.parseOutcome("NoSuchOutcome"));
    }

    @Test
    public void explicitUnknownIsStillAUsableFilter() {
        // But asking FOR unmapped records is legitimate -- it is how you find what the enum is missing.
        Assert.assertEquals(AuditViewCommands.parseAction("UNKNOWN"), AuditAction.UNKNOWN);
        Assert.assertEquals(AuditViewCommands.parseOutcome("UNKNOWN"), AuditOutcome.UNKNOWN);
    }

    @Test
    public void realFiltersParse() {
        Assert.assertEquals(AuditViewCommands.parseAction("LOGIN"), AuditAction.LOGIN);
        Assert.assertEquals(AuditViewCommands.parseAction("login"), AuditAction.LOGIN);
        Assert.assertEquals(AuditViewCommands.parseOutcome("FAILURE"), AuditOutcome.FAILURE);
    }

    @Test
    public void csvEscapesTheThingsThatBreakSpreadsheets() {
        Assert.assertEquals(AuditViewCommands.escape("plain"), "plain");
        Assert.assertEquals(AuditViewCommands.escape(null), "");
        Assert.assertEquals(AuditViewCommands.escape("a,b"), "\"a,b\"");
        Assert.assertEquals(AuditViewCommands.escape("say \"hi\""), "\"say \"\"hi\"\"\"");
    }

    @Test
    public void csvEscapesEmbeddedNewlines() {
        // Not hypothetical: the imported PayPal records contain newlines, which is exactly why the original
        // log files needed continuation-line handling. Unquoted, each one becomes a bogus extra CSV row.
        Assert.assertEquals(AuditViewCommands.escape("PayPal payment.\nFee: $0.53"),
                "\"PayPal payment.\nFee: $0.53\"");
    }

    @Test
    public void dropdownsOfferEveryValue() {
        Assert.assertEquals(view.getActions().size(), AuditAction.values().length,
                "Every action must be filterable, or some records become unreachable from the UI");
        Assert.assertEquals(view.getOutcomes().size(), AuditOutcome.values().length);
    }
}
