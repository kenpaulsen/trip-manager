package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Data;
import org.paulsens.trip.util.Util;

/**
 * One named, typed hole in a {@link ContentTemplate} body, written as <code>{{name}}</code> in the HTML.
 * The name and type drive the content editor: each placeholder becomes a prompt (text field, WYSIWYG
 * editor, URL field) and the type picks the escaping rule when the filled value is rendered -- see
 * {@code ContentRenderer}.
 *
 * <p>Mutable ({@code @Data}) because the template dialog edits placeholder rows in place; the constructor
 * normalizes, and {@code TemplateCommands.saveTemplate} re-normalizes whatever the dialog produced.
 */
@Data
public final class Placeholder implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /**
     * The value's kind, which decides both the editing widget and the rendering rule:
     * {@link #TEXT} is HTML-escaped, {@link #RICH_TEXT} renders verbatim (WYSIWYG-authored),
     * {@link #IMAGE_URL} must parse as http(s) and is attribute-escaped, {@link #URL} (a link target)
     * additionally admits a site-relative path ({@code /trip/...}, never {@code //host}) or a page
     * fragment ({@code #benefits}), and {@link #VIDEO_URL} normalizes YouTube links to their embed form.
     * {@link #CHOICE} is a fixed pick-one prompt whose options come from a programmatic type's
     * {@code choicesFor} provider, or on a hand-authored template from its own {@link #options} list
     * (escaped like TEXT when rendered). {@link #MULTI_CHOICE} is its pick-many
     * sibling: the editor shows a checkbox menu over the same provider, and the picks are stored in the
     * one string value comma-separated ({@code ContentInstance.getListValues()} is the list view).
     * {@link #PERSON} picks one person through an autocomplete and stores that person's ID (never a name or
     * email, which the person may change); like CHOICE it only means something to a programmatic type,
     * whose fragment resolves the id at render.
     */
    public enum Type {
        TEXT, RICH_TEXT, IMAGE_URL, VIDEO_URL, URL, CHOICE, MULTI_CHOICE, PERSON
    }

    /**
     * The prompt kinds only a programmatic type can declare: their options (or, for PERSON, the people an
     * editor may pick) come from code, not from anything a hand-authored template could supply.
     */
    public static boolean isProviderBacked(final Type type) {
        return type == Type.CHOICE || type == Type.MULTI_CHOICE || type == Type.PERSON;
    }

    /**
     * The one {@link #format} there is: a CSS length, a number with a unit ({@code 20px}, {@code 1.5em},
     * {@code 40%}) or a bare 0. A prompt so marked may be written into a {@code style} attribute, because nothing
     * but a length can ever reach it ({@link #isLength}).
     */
    public static final String FORMAT_CSS_LENGTH = "css-length";

    /** What a {@link #FORMAT_CSS_LENGTH} value must be: the mail cleaner's length shape, plus vh and vw. */
    private static final Pattern CSS_LENGTH = Pattern.compile("(?:\\d{1,5}(?:\\.\\d{1,3})?(?:px|em|rem|%|vh|vw)|0)");

    /** Token name matched in the template body: {@code {{name}}}. */
    private String name;
    private Type type;
    /** The prompt shown in the content editor, e.g. "Video URL". */
    private String label;
    /** Optional help/placeholder text for the editor field. */
    private String hint;
    private boolean required;
    /**
     * The value the template picker's thumbnail shows for this prompt (2026-11), so a card renders the template
     * as a filled-in section rather than an empty skeleton; null falls back to the label. Never a URL: the
     * renderer's URL rules would refuse a placeholder and an {@code https} sample would fetch something real.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sample;
    /**
     * A hand-written template's fixed choices (2026-11-25): a prompt with options is a {@link Type#CHOICE} the
     * editor offers as a menu of exactly these values (a band's tone), where a programmatic type's CHOICE asks
     * its provider instead. Written only when there are some, so every older row stays byte for byte; a row read
     * back from an older session stream has null here, which {@link #getOptions} answers as none.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> options;
    /** A check on the typed value ({@link #FORMAT_CSS_LENGTH}), or null for none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String format;

    private Placeholder() {
    }

    /** The shape before {@link #sample} existed: every stored row, and every provider-backed property. */
    public Placeholder(final String name, final Type type, final String label, final String hint,
            final boolean required) {
        this(name, type, label, hint, required, null);
    }

    /** The shape before {@link #options} and {@link #format} existed. */
    public Placeholder(final String name, final Type type, final String label, final String hint,
            final boolean required, final String sample) {
        this(name, type, label, hint, required, sample, null, null);
    }

    @JsonCreator
    public Placeholder(
            @JsonProperty("name") final String name,
            @JsonProperty("type") final Type type,
            @JsonProperty("label") final String label,
            @JsonProperty("hint") final String hint,
            @JsonProperty("required") final boolean required,
            @JsonProperty("sample") final String sample,
            @JsonProperty("options") final List<String> options,
            @JsonProperty("format") final String format) {
        this.name = name == null ? "" : name.trim();
        this.type = type == null ? Type.TEXT : type;
        this.label = (label == null || label.isBlank()) ? this.name : label;
        this.hint = hint;
        this.required = required;
        this.sample = (sample == null || sample.isBlank()) ? null : sample;
        this.options = cleanOptions(options);
        this.format = Util.trimToNull(format);
    }

    /** @return an independent copy, so editing a template copy cannot mutate the cached original's rows. */
    public Placeholder copy() {
        return new Placeholder(name, type, label, hint, required, sample, options, format);
    }

    /** The fixed choices, in order; empty (never null) when there are none. */
    public List<String> getOptions() {
        return options == null ? List.of() : options;
    }

    public void setOptions(final List<String> options) {
        this.options = cleanOptions(options);
    }

    /** Whether this prompt is a menu of the template's own fixed values ({@link #options}). */
    @JsonIgnore
    public boolean isFixedChoice() {
        return type == Type.CHOICE && !getOptions().isEmpty();
    }

    /**
     * The options as the template editor's one text field shows them: comma-separated. Not stored: the
     * {@link #options} list is.
     */
    @JsonIgnore
    public String getOptionsText() {
        return String.join(", ", getOptions());
    }

    @JsonIgnore
    public void setOptionsText(final String text) {
        setOptions(text == null ? null : List.of(text.split(",")));
    }

    /** Whether a typed value may be written into a {@code style} attribute as a length. */
    public static boolean isLength(final String value) {
        return value != null && CSS_LENGTH.matcher(value.trim()).matches();
    }

    /** Trimmed, blank and repeated entries dropped, in the order given; null when nothing is left. */
    private static List<String> cleanOptions(final List<String> raw) {
        if (raw == null) {
            return null;
        }
        final List<String> kept = new ArrayList<>();
        for (final String option : raw) {
            final String value = option == null ? "" : option.trim();
            if (!value.isEmpty() && !kept.contains(value)) {
                kept.add(value);
            }
        }
        // A plain ArrayList: the session's serializer rebuilds it without trouble, unlike the JDK's immutable lists.
        return kept.isEmpty() ? null : kept;
    }
}
