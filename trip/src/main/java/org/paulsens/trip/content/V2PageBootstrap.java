package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.ContentInstance;

/**
 * The production skeleton of the v2 landing page ({@code page:trip-index}): the section rows
 * {@code bootstrap-home-v2.sh} conditionally installs on a fresh environment. The SOURCE OF TRUTH for that
 * script's payloads -- regenerate the script rather than hand-editing its JSON. Container ids are the
 * page's anchor targets ({@code events}, {@code docs}), so deep links keep working; editor privileges
 * mirror the v1 grants (eventAdmin runs Events, mediaAdmin runs Documents).
 *
 * <p>Bodies here are real page content, not fake data: the title block is the site header; the intro is a
 * placeholder an admin replaces in edit mode. Version 0 -- the save path (or the script's generated row)
 * assigns v1.
 */
public final class V2PageBootstrap {

    public static final String PAGE_KEY = "page:trip-index";

    private V2PageBootstrap() {
    }

    public static List<ContentInstance> rows() {
        return List.of(
                text("home-title", PAGE_KEY, "Title block", 0,
                        "<div style=\"text-align:center\">"
                                + "<h1 style=\"margin-bottom:0px;\">Visit Queen of Peace</h1>"
                                + "<span style=\"font-size:1.3em\">by Center for Peace West</span></div>"),
                text("home-intro", PAGE_KEY, "Introduction", 1,
                        "<p>Welcome! Edit this introduction from the page's edit mode.</p>"),
                container("events", "Events", 2, "eventAdmin", null),
                programmatic("home-pilgrimages-en", "English Medjugorje Pilgrimages", 3,
                        StarterTemplates.PILGRIMAGES_ID,
                        Map.of("language", "English", "cfpwOnly", "false")),
                programmatic("home-pilgrimages-es", "Peregrinaciones españolas a Medjugorje", 4,
                        StarterTemplates.PILGRIMAGES_ID,
                        Map.of("language", "Spanish", "cfpwOnly", "false")),
                container("reflection", "", 5, null, null),
                programmatic("home-albums", "Pilgrimage Pictures", 6,
                        StarterTemplates.PHOTO_ALBUMS_ID, Map.of()),
                // Documents admits only File items, so its Add button skips the template picker.
                container("docs", "Documents", 7, "mediaAdmin", List.of(StarterTemplates.FILE_ID)));
    }

    private static ContentInstance text(final String id, final String section, final String title,
            final int position, final String body) {
        return new ContentInstance(id, section, title, StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", body)), null, position, 0, null, "bootstrap-script");
    }

    private static ContentInstance container(final String id, final String title, final int position,
            final String editorPrivilege, final List<String> allowedChildTemplateIds) {
        return new ContentInstance(id, PAGE_KEY, title, StarterTemplates.CONTAINER_ID, 1,
                new HashMap<>(), null, position, 0, null, "bootstrap-script",
                editorPrivilege == null ? null : List.of(editorPrivilege), allowedChildTemplateIds);
    }

    private static ContentInstance programmatic(final String id, final String title, final int position,
            final String templateId, final Map<String, String> values) {
        return new ContentInstance(id, PAGE_KEY, title, templateId, 1,
                new HashMap<>(values), null, position, 0, null, "bootstrap-script");
    }
}
