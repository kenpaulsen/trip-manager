package org.paulsens.trip.content;

import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class CssClassScanTest {

    @Test
    public void everyClassAttributeIsReadInOrderWithoutDuplicates() {
        final String html = "<div class=\"contentItem band band-tint\"><p class='pw-note  lead'>x</p>"
                + "<span CLASS=\"band\">y</span><em class=\"#{el.value}\">z</em></div>";
        assertEquals(List.copyOf(CssClassScan.classesIn(html)),
                List.of("contentItem", "band", "band-tint", "pw-note", "lead"));
        assertTrue(CssClassScan.classesIn(null).isEmpty());
        assertTrue(CssClassScan.classesIn("<p>no classes</p>").isEmpty());
    }

    @Test
    public void selectorsAreReadOutsideDeclarationBlocksAndComments() {
        final String css = "/* .commented {} */ .contentItem .pw-note { font-size: 1.5em; color: red }\n"
                + "@media (max-width: 600px) { .band-heading, .hero .eyebrow { margin: .5em } }\n"
                + ".a.b:hover > .c {}";
        final Set<String> found = CssClassScan.selectorsIn(css);
        assertEquals(List.copyOf(found), List.of("contentItem", "pw-note", "band-heading", "hero", "eyebrow",
                "a", "b", "c"));
        assertFalse(found.contains("5em"), "a value is never a class");
        assertFalse(found.contains("commented"));
        assertTrue(CssClassScan.selectorsIn(null).isEmpty());
    }

    @Test
    public void frameworkClassesAreNeverCustom() {
        assertTrue(CssClassScan.isFrameworkClass("ui-button"));
        assertTrue(CssClassScan.isFrameworkClass("pi-check"));
        assertTrue(CssClassScan.isFrameworkClass("p-mt-2"));
        assertTrue(CssClassScan.isFrameworkClass("col-6"));
        assertTrue(CssClassScan.isFrameworkClass("ql-editor"));
        assertTrue(CssClassScan.isFrameworkClass(""));
        assertFalse(CssClassScan.isFrameworkClass("pw-note"));
        assertFalse(CssClassScan.isFrameworkClass("band"));
    }
}
