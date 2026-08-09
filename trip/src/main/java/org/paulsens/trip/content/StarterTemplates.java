package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;

/**
 * The three built-in content templates, shared by the "Install starter templates" bootstrap action and the
 * local-mode fake data. Each call returns fresh instances (templates are mutable) at version 0 -- the DAO
 * assigns the real version on save.
 */
public final class StarterTemplates {

    public static final String YOUTUBE_VIDEO_ID = "youtube-video";
    public static final String IMAGE_ID = "image";
    public static final String TEXT_ONLY_ID = "text-only";

    /** Ids never deleted by the cleanup script without {@code --include-starters}. */
    public static final List<String> IDS = List.of(YOUTUBE_VIDEO_ID, IMAGE_ID, TEXT_ONLY_ID);

    private StarterTemplates() {
    }

    public static List<ContentTemplate> all() {
        return List.of(youtubeVideo(), image(), textOnly());
    }

    private static ContentTemplate youtubeVideo() {
        final String body = """
                <div class="contentItem contentVideo">
                    <div style="position:relative;width:100%;max-width:840px;margin:0 auto;aspect-ratio:16/9;">
                        <iframe style="position:absolute;top:0;left:0;width:100%;height:100%;"
                                src="{{videoUrl}}" title="{{caption}}" frameborder="0"
                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope;
                                picture-in-picture; web-share" allowfullscreen="allowfullscreen"></iframe>
                    </div>
                    <div style="text-align:center;font-size:1.05em;margin-top:6px;">{{caption}}</div>
                </div>
                """;
        return new ContentTemplate(YOUTUBE_VIDEO_ID, 0, "YouTube Video",
                "A responsively-sized YouTube video with a caption underneath.", body,
                List.of(new Placeholder("videoUrl", Placeholder.Type.VIDEO_URL, "Video URL",
                                "Paste any YouTube link (watch, share, or shorts form)", true),
                        new Placeholder("caption", Placeholder.Type.TEXT, "Caption",
                                "Short text shown under the video", false)),
                null, null);
    }

    private static ContentTemplate image() {
        final String body = """
                <div class="contentItem contentImage" style="text-align:center;">
                    <a href="{{linkUrl}}" target="_blank" rel="noopener">
                        <img src="{{imageUrl}}" alt="{{altText}}"
                                style="max-width:100%;height:auto;" loading="lazy" />
                    </a>
                    <div style="font-size:1.05em;margin-top:6px;">{{caption}}</div>
                </div>
                """;
        return new ContentTemplate(IMAGE_ID, 0, "Image",
                "A centered image, optionally linked, with a rich-text caption.", body,
                List.of(new Placeholder("imageUrl", Placeholder.Type.IMAGE_URL, "Image URL",
                                "Where the image lives (e.g. the files CDN)", true),
                        new Placeholder("linkUrl", Placeholder.Type.URL, "Link URL",
                                "Optional page to open when the image is clicked", false),
                        new Placeholder("altText", Placeholder.Type.TEXT, "Alt text",
                                "Short description for screen readers", true),
                        new Placeholder("caption", Placeholder.Type.RICH_TEXT, "Caption",
                                "Optional rich-text caption under the image", false)),
                null, null);
    }

    private static ContentTemplate textOnly() {
        final String body = """
                <div class="contentItem contentText">{{body}}</div>
                """;
        return new ContentTemplate(TEXT_ONLY_ID, 0, "Text Only",
                "A free-form rich-text block.", body,
                List.of(new Placeholder("body", Placeholder.Type.RICH_TEXT, "Body",
                        "The text to show", true)),
                null, null);
    }
}
