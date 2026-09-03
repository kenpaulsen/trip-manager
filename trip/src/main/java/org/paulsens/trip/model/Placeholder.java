package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

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
     * {@code choicesFor} provider (escaped like TEXT when rendered); it is meaningless on hand-authored
     * templates and excluded from the template editor's type menu. {@link #MULTI_CHOICE} is its pick-many
     * sibling: the editor shows a checkbox menu over the same provider, and the picks are stored in the
     * one string value comma-separated ({@code ContentInstance.getListValues()} is the list view).
     */
    public enum Type {
        TEXT, RICH_TEXT, IMAGE_URL, VIDEO_URL, URL, CHOICE, MULTI_CHOICE
    }

    /** The prompt kinds only a programmatic type can declare (options come from its provider). */
    public static boolean isProviderBacked(final Type type) {
        return type == Type.CHOICE || type == Type.MULTI_CHOICE;
    }

    /** Token name matched in the template body: {@code {{name}}}. */
    private String name;
    private Type type;
    /** The prompt shown in the content editor, e.g. "Video URL". */
    private String label;
    /** Optional help/placeholder text for the editor field. */
    private String hint;
    private boolean required;

    private Placeholder() {
    }

    @JsonCreator
    public Placeholder(
            @JsonProperty("name") final String name,
            @JsonProperty("type") final Type type,
            @JsonProperty("label") final String label,
            @JsonProperty("hint") final String hint,
            @JsonProperty("required") final boolean required) {
        this.name = name == null ? "" : name.trim();
        this.type = type == null ? Type.TEXT : type;
        this.label = (label == null || label.isBlank()) ? this.name : label;
        this.hint = hint;
        this.required = required;
    }

    /** @return an independent copy, so editing a template copy cannot mutate the cached original's rows. */
    public Placeholder copy() {
        return new Placeholder(name, type, label, hint, required);
    }
}
