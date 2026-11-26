package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.HtmlCheck;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.content.MarkupLoss;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.PageHtml;
import org.paulsens.trip.content.PersonType;
import org.paulsens.trip.content.ProgrammaticContentTemplate;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.RichTextRules;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.content.StripNotes;
import org.paulsens.trip.content.TemplateValueMigrator;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.TemplateRecord;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.ScopeUtil;

/**
 * Template-driven page content, exposed to pages as {@code #{content}}.
 *
 * <p>A page is a section key (e.g. {@code page:trip-index}) whose ordered instances the contentSections
 * include renders; a CONTAINER instance's children live under the container's own id as their section key.
 * Who may edit what: {@code contentAdmin} (or a site admin) edits everything; a container instance may name
 * {@code editorPrivileges} -- holders of ANY of them edit that container's CHILDREN. The save/delete paths
 * re-check server-side -- the page's {@code rendered=} gate only hides buttons.
 *
 * <p>Performance rule: everything reachable from a public page render answers from the
 * stale-while-revalidate caches ({@code getForSection}, {@code childrenFor}, template lookups). The
 * uncached point reads here ({@code getContent}, container resolution in {@code canEdit}) sit behind
 * edit-mode short-circuits or admin dialogs only.
 */
@Slf4j
@Named("content")
@ApplicationScoped
public class ContentCommands {

    /** The ceiling of {@code content.versions.retained} (see retainCount): no template keeps more versions. */
    private static final int MAX_RETAINED_VERSIONS = 50;
    private final PrivilegeCommands priv = new PrivilegeCommands();
    private final ConfigCommands config = new ConfigCommands();
    /** The most people one keystroke of the content dialog's person picker offers. */
    static final int PERSON_PICKER_LIMIT = 25;
    /** Test seam (like {@code ChatCommands}' limiter): the caller behind the current request. */
    @Setter
    private Supplier<Caller> callerSource = Caller::current;

    /** The publicly-visible instances of a section, in display order -- expired event dates drop off here. */
    public List<ContentInstance> getForSection(final String section) {
        final LocalDateTime now = LocalDateTime.now();
        return readSection(section).stream()
                .filter(c -> c.isVisibleAt(now))
                .toList();
    }

    /** Every instance of a section, including expired ones -- the editor's view. */
    public List<ContentInstance> getAllForSection(final String section) {
        return readSection(section);
    }

    /**
     * One instance by id, as an editable copy. Follows the bean convention of never returning null -- an
     * unknown id answers a blank instance with a fresh id (callers save through {@link #saveContent}, which
     * refuses a blank section, so a junk row cannot result from rendering one of these).
     */
    public ContentInstance getContent(final String id) {
        try {
            return DAO.getInstance().getContent(id, Cached.NO).map(ContentInstance::copy)
                    .map(this::followingLatest).orElseGet(ContentCommands::blank);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up content: " + id, ex);
            return blank();
        }
    }

    /**
     * A new, unsaved instance for the dialog: pinned to the template's CURRENT version, with an empty value
     * per declared placeholder and a position after the section's existing instances.
     */
    public ContentInstance createContent(final String section, final String templateId) {
        final ContentTemplate template = DAO.getInstance().getTemplate(templateId, Cached.NO).orElse(null);
        final ContentInstance created = blank();
        created.setSection(section);
        if (template != null) {
            created.setTemplateId(template.getId());
            created.setTemplateVersion(template.getVersion());
            ProgrammaticTypes.placeholdersOf(template)
                    .forEach(ph -> created.getValues().putIfAbsent(ph.getName(), ""));
            if (template.getKind() == TemplateKind.CONTAINER && template.getAllowedChildTemplateIds() != null
                    && !template.getAllowedChildTemplateIds().isEmpty()) {
                // A new container STARTS with its template's allow-list checked, so the dialog shows what
                // the template decided and the editor overrides by editing it -- an empty control used to
                // read as "nothing configured" when the template had in fact configured two types.
                created.setAllowedChildTemplateIds(new ArrayList<>(template.getAllowedChildTemplateIds()));
            }
        } else {
            log.warn("Creating content in '{}' against unknown template '{}'", section, templateId);
            created.setTemplateId(templateId);
        }
        created.setPosition(readSection(section).size());
        return created;
    }

    /**
     * Saves (creating or updating); refused without edit rights on the instance's section, when a container
     * child breaks the container's rules, or when authored HTML is structurally broken.
     */
    public boolean saveContent(final ContentInstance instance) {
        if (instance == null || instance.getSection() == null || instance.getSection().isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, instance.getSection())) {
            log.warn("Refusing content save in '{}': caller lacks edit rights", instance.getSection());
            PageFeedback.error(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.contentCommands.noPermission"));
            return false;
        }
        guardContainerConfig(caller, instance);
        // A Stay on Latest instance is saved against the CURRENT version (the template may have moved on while the
        // dialog was open, and a script or the API may send an older one): the checks below read that version.
        reportCarry(instance, followLatest(instance, Cached.NO), false);
        final String problem = validateForSave(instance);
        if (problem != null) {
            log.warn("Refusing content save of '{}': {}", instance.getId(), problem);
            PageFeedback.error(Text.get("feedback.shared.notSaved"), problem);
            return false;
        }
        final List<String> removed = cleanAuthoredHtml(instance);
        publishIfSeededPlaceholder(instance);
        instance.setModifiedBy(caller.auditActor().email());
        final boolean saved;
        try {
            saved = storeContent(instance, retainCount());
        } catch (final RuntimeException ex) {
            log.error("Unable to save content: " + instance.getId(), ex);
            return false;
        }
        if (!saved) {
            PageFeedback.error(Text.get("feedback.shared.notSaved"), Text.get("feedback.contentCommands.saveFailed"));
        }
        if (saved) {
            warnOfRemovals(removed);
            audit(caller, instance.getId(),
                    "Saved v" + instance.getVersion() + " of '" + instance.getTitle() + "' in "
                            + instance.getSection());
            publishContainerOf(caller, instance);
        }
        return saved;
    }

    /**
     * The content drawer's Apply. A save that would remove anything from what was typed does not happen on the
     * first press: the drawer is told to ask ({@code args.stripAsk}), shows exactly what would go, and saves only
     * when the author answers "Remove it and save", which sends back the key of THAT list
     * ({@link #removalsKey}). Typing something else that would be removed changes the key, so it is asked again;
     * "Go back" leaves the form holding what was typed. Markup that is broken is refused by the save itself,
     * with its own message, before any of this.
     *
     * @return whether the item was saved
     */
    public boolean applyFromDrawer(final ContentInstance working, final String beforeId, final String okKey) {
        if (working != null && validateAuthoredHtml(working) == null) {
            final List<String> removed = wouldRemove(working);
            if (!removed.isEmpty() && !keyOf(removed).equals(okKey)) {
                // The drawer is NOT redrawn for the question: redrawing rebuilds its editors, and an author who
                // typed in the HTML view would come back from "Go back and change it" to the Visual view, where
                // the script or handler they were told about is not shown and an edit rewrites their markup.
                PageFeedback.callbackParam("stripAsk", true);
                return false;
            }
        }
        PageFeedback.alsoRender(DRAWER);
        return saveNewContentAt(working, beforeId);
    }

    /** The content drawer's client id: redrawn after a save or a refusal, never for the question above. */
    private static final String DRAWER = "form:contentDlg";

    /** Names one exact list of removals, so that an author's OK covers that list and no other. */
    public String removalsKey(final ContentInstance working) {
        return keyOf(wouldRemove(working));
    }

    private static String keyOf(final List<String> removed) {
        return removed.isEmpty() ? "" : Integer.toHexString(removed.hashCode());
    }

    /** Says after a save exactly what the allowlist took out, so that it is never a surprise found later. */
    private static void warnOfRemovals(final List<String> removed) {
        if (!removed.isEmpty()) {
            PageFeedback.warn(StripNotes.savedNotice(removed));
        }
    }

    /**
     * {@link #saveContent} for the Add flow's "Add section here": a NEW item is saved in front of
     * {@code beforeId} (an item of the same section) rather than at the end. The item takes that place's position
     * before it is saved, and the rest of the section is then renumbered through {@link #applyOrder}, the
     * arrange dialog's own version-silent path; the new id is spliced into the order by hand, because a read
     * right after a write may not list it yet. An item that already exists (an edit carrying a stale
     * {@code beforeId}), a blank {@code beforeId}, and one that is not in the section (deleted meanwhile, or
     * left over from another Add) all save exactly as {@link #saveContent} does.
     */
    public boolean saveNewContentAt(final ContentInstance instance, final String beforeId) {
        if (instance == null || instance.getSection() == null || beforeId == null || beforeId.isBlank()
                || instance.getVersion() > 0) {
            return saveContent(instance);
        }
        final ArrayList<String> order = idsFor(instance.getSection(), true);
        order.remove(instance.getId());
        final int at = order.indexOf(beforeId.trim());
        if (at < 0) {
            return saveContent(instance);
        }
        final int atEnd = instance.getPosition();
        instance.setPosition(at);
        if (!saveContent(instance)) {
            instance.setPosition(atEnd);
            return false;
        }
        order.add(at, instance.getId());
        if (!applyOrder(instance.getSection(), order)) {
            PageFeedback.warn(Text.get("feedback.contentCommands.savedNotPlaced"));
        }
        return true;
    }

    /**
     * The page anchor to land on after an item is ADDED ({@code contentSections.xhtml} writes one per page
     * section, {@code <a id="...">}): the new section's own id, or for an item added inside a group the group's.
     * "" for an item that already exists (an edit reloads in place) and for anything that is not on the page.
     * Asked BEFORE the save, while the item is still new.
     */
    public String landingAnchor(final ContentInstance instance, final String pageKey) {
        if (instance == null || instance.getVersion() > 0 || instance.getSection() == null || pageKey == null) {
            return "";
        }
        return instance.getSection().equals(pageKey) ? instance.getId() : instance.getSection();
    }

    /**
     * The first edit of a seeded placeholder publishes it (docs/conference.md, "Placeholders start hidden"): the
     * passed "Show until" that hid it was the seed's, not the editor's, so when the editor left that date exactly as
     * seeded it is cleared. A date the editor set themselves (any other value) is kept.
     */
    private void publishIfSeededPlaceholder(final ContentInstance instance) {
        final ContentInstance stored = storedRow(instance.getId());
        if (OrgPageBootstrap.isSeededPlaceholder(stored)
                && leftAsSeeded(stored.getEventDate(), instance.getEventDate())) {
            instance.setEventDate(null);
        }
    }

    /**
     * Whether the editor left the seed's "Show until" alone: cleared it, or kept it to the minute. The content
     * dialog's calendar carries minutes only ({@code MM/dd/yyyy HH:mm}), so an untouched seed date comes back with
     * its seconds dropped; comparing to the second would mistake every untouched date for the editor's own.
     */
    private static boolean leftAsSeeded(final LocalDateTime seeded, final LocalDateTime edited) {
        return edited == null
                || seeded.truncatedTo(ChronoUnit.MINUTES).equals(edited.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * Saving the first item into a seeded placeholder CONTAINER (a speaker into Speakers, a question into Questions)
     * publishes the container too: the editor filled it in, which is the publishing act, and a visible card inside
     * a hidden band would be no card at all. Its own row, stamped with the caller and audited like any save.
     */
    private void publishContainerOf(final Caller caller, final ContentInstance child) {
        final ContentInstance container = containerOf(child.getSection());
        if (!OrgPageBootstrap.isSeededPlaceholder(container)) {
            return;
        }
        container.setEventDate(null);
        container.setModifiedBy(caller.auditActor().email());
        try {
            if (storeContent(container, retainCount())) {
                audit(caller, container.getId(), "Published '" + container.getTitle()
                        + "' (a seeded placeholder) with its first item");
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to publish the container of content: " + child.getId(), ex);
        }
    }

    /** The content dialog's note: whether the row being edited is still a seeded placeholder. */
    public boolean isSeededPlaceholder(final ContentInstance instance) {
        return OrgPageBootstrap.isSeededPlaceholder(instance);
    }

    private static ContentInstance storedRow(final String id) {
        try {
            return DAO.getInstance().getContent(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the stored content: " + id, ex);
            return null;
        }
    }

    /**
     * Only a contentAdmin may change a container's admin-only config: its editor privileges (never its
     * own editors) and its child-template allow-list. A contentAdmin's chips are free-typed, so they are
     * sanitized instead: names matching no stored privilege are IGNORED on save by design (the dialog
     * flags them red while editing).
     */
    private void guardContainerConfig(final Caller caller, final ContentInstance instance) {
        if (caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            sanitizeEditorPrivileges(instance);
            return;
        }
        final ContentInstance stored = DAO.getInstance().getContent(instance.getId(), Cached.NO).orElse(null);
        instance.setEditorPrivileges(stored == null ? null : stored.getEditorPrivileges());
        instance.setAllowedChildTemplateIds(stored == null ? null : stored.getAllowedChildTemplateIds());
    }

    private void sanitizeEditorPrivileges(final ContentInstance instance) {
        if (instance.getEditorPrivileges() == null) {
            return;
        }
        final List<String> valid = instance.getEditorPrivileges().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .filter(this::privilegeExists)
                .toList();
        instance.setEditorPrivileges(valid.isEmpty() ? null : valid);
    }

    private boolean privilegeExists(final String name) {
        return priv.getPrivilege(name, null) != Privilege.NONE;
    }

    /**
     * Cleans the title (when it carries markup) and every RICH_TEXT value, AFTER validation so the author's own
     * markup is what got checked: the editor's tidy-up ({@link RichTextRules}) and then the allowlist
     * ({@link PageHtml}, medjugorje#158). Runs on the way to storage as well as at render time on purpose: the
     * stored row is what the Source view, the REST API and the next editor all read, and a value that renders
     * clean but reads dirty is the same trap twice.
     *
     * @return what the allowlist removed, in the author's words ({@link StripNotes}); empty when nothing was
     */
    private List<String> cleanAuthoredHtml(final ContentInstance instance) {
        final List<String> removed = new ArrayList<>();
        if (PageHtml.titleHasMarkup(instance.getTitle())) {
            final PageHtml.Cleaned title = PageHtml.storedTitle(instance.getTitle());
            instance.setTitle(title.body());
            removed.addAll(StripNotes.lines(Text.get("common.title"), title));
        }
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null) {
            return removed;
        }
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            if (ph.getType() == Placeholder.Type.RICH_TEXT) {
                removed.addAll(cleanValue(instance, ph));
            }
        }
        return removed;
    }

    private static List<String> cleanValue(final ContentInstance instance, final Placeholder ph) {
        final String stored = instance.getValues().get(ph.getName());
        final String tidied = RichTextRules.normalize(stored);
        if (tidied == null) {
            return List.of();
        }
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(tidied);
        if (!cleaned.body().equals(stored)) {
            instance.getValues().put(ph.getName(), cleaned.body());
        }
        return StripNotes.lines(ph.getLabel(), cleaned);
    }

    /**
     * What a save of this working copy WOULD remove, in the author's words: the content drawer shows it under
     * the form while the author types (the owner's rule, 2026-11-21: say exactly what is going, while they can
     * still go back and change it; never strip silently later). Works on a copy, so the form keeps what was
     * typed. Empty when nothing would go.
     */
    public List<String> wouldRemove(final ContentInstance working) {
        if (working == null) {
            return List.of();
        }
        // One redraw of the drawer asks four times about the same working copy (and Apply asks again before it), and
        // each answer costs a fresh read of the stored row and a reading of every rich-text value: the answer is kept
        // for the request, for exactly the copy it was given for. A save forgets it ({@link #storeContent}).
        final Map<String, Object> memo = requestMemo();
        if (memo != null && memo.get(WOULD_REMOVE_MEMO) instanceof WouldRemove(var asked, var answer)
                && asked.equals(working)) {
            return answer;
        }
        final List<String> removed = computeWouldRemove(working);
        RequestMemo.put(memo, WOULD_REMOVE_MEMO, new WouldRemove(working.copy(), removed));
        return removed;
    }

    private List<String> computeWouldRemove(final ContentInstance working) {
        try {
            final List<String> removed = new ArrayList<>(cleanAuthoredHtml(working.copy()));
            removed.addAll(editorLosses(working));
            return List.copyOf(removed);
        } catch (final RuntimeException ex) {
            log.warn("Unable to preview what a save of content " + working.getId() + " would remove", ex);
            return List.of();
        }
    }

    /** {@link #wouldRemove}'s answer for one working copy, as it was when asked. */
    private record WouldRemove(ContentInstance asked, List<String> answer) {
    }

    private static final String WOULD_REMOVE_MEMO = "content.wouldRemove";

    /**
     * Seam: {@link #wouldRemove}'s memo map, the Faces request map ({@link RequestMemo}); null off a page, where
     * every call computes.
     */
    protected Map<String, Object> requestMemo() {
        return RequestMemo.facesRequestMap();
    }

    /** Every write of a content row: what {@link #wouldRemove} answered was compared with the row as it was. */
    private boolean storeContent(final ContentInstance instance, final int retain) {
        RequestMemo.forget(requestMemo(), WOULD_REMOVE_MEMO);
        return DAO.getInstance().saveContent(instance, retain);
    }

    /**
     * Formatting removed from a stored rich-text value while its text stayed (an image's float, a styled wrapper;
     * {@link MarkupLoss}), one line per lost tag, so that Apply asks before saving it. Compared with the editor's
     * value tidied the way a save tidies it, and before the allowlist runs, so that nothing the allowlist removes is
     * reported twice. A new item has nothing stored to lose, and a failed read ({@link #storedRow} logs it) asks
     * nothing here while the allowlist's own lines still stand. A value too large to compare gets one line saying
     * so, in place of its losses.
     */
    private List<String> editorLosses(final ContentInstance working) {
        final ContentInstance stored = working.getId() == null ? null : storedRow(working.getId());
        final ContentTemplate template = stored == null ? null : resolveTemplate(working);
        if (template == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            if (ph.getType() == Placeholder.Type.RICH_TEXT) {
                final String typed = RichTextRules.normalize(working.getValues().get(ph.getName()));
                // Both sides tidied alike: a row stored before saves were tidied may still hold Quill's alignment
                // class, which the typed side's tidy turns into the inline style, and would otherwise read as lost.
                final MarkupLoss.Check check = MarkupLoss.check(
                        RichTextRules.normalize(stored.getValues().get(ph.getName())), typed);
                for (final String tag : check.lost()) {
                    lines.add(Text.get("server.stripNotes.line", ph.getLabel(),
                            Text.get("server.stripNotes.formattingRemoved", tag)));
                }
                if (check.skipped()) {
                    // Too large to compare within what one request may spend: said, so that Apply still asks
                    // (the owner's rule, 2026-11-23: never a silent skip of the check either).
                    lines.add(Text.get("server.stripNotes.line", ph.getLabel(),
                            Text.get("server.stripNotes.notChecked")));
                }
            }
        }
        return lines;
    }

    /**
     * Structural/containment validation. @return a user-facing problem, or null when the save may proceed.
     */
    private String validateForSave(final ContentInstance instance) {
        if (instance.getId() != null && instance.getId().equals(instance.getSection())) {
            return Text.get("feedback.contentCommands.insideItself");
        }
        final String requiredProblem = validateRequired(instance);
        if (requiredProblem != null) {
            return requiredProblem;
        }
        final String shapeProblem = validateShapes(instance);
        if (shapeProblem != null) {
            return shapeProblem;
        }
        final String htmlProblem = validateAuthoredHtml(instance);
        if (htmlProblem != null) {
            return htmlProblem;
        }
        final String personProblem = validatePersonValues(instance);
        if (personProblem != null) {
            return personProblem;
        }
        final ContentInstance parent;
        try {
            parent = DAO.getInstance().getContent(instance.getSection(), Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the parent of content: " + instance.getId(), ex);
            return Text.get("feedback.contentCommands.parentUnchecked");
        }
        return parent == null ? null : validateAsChild(instance, parent);
    }

    /**
     * A required prompt must hold something (2026-11): the dialog refuses first through JSF's own required
     * validation, so this is what the API and any script path see. An empty rich-text editor submits "" or an
     * empty paragraph, which counts as blank.
     */
    private String validateRequired(final ContentInstance instance) {
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null) {
            return null;
        }
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            if (isRequired(ph) && showsProperty(instance, ph.getName())
                    && isBlankValue(ph, instance.getValues().get(ph.getName()))) {
                return Text.get("feedback.contentCommands.required", promptLabel(ph));
            }
        }
        return null;
    }

    /**
     * Whether a prompt must be filled in before a save (owner, 2026-11-25): what the template says, except that a
     * rich-text prompt never is. A blank Content renders an empty section, which is harmless, while refusing the
     * save cost an editor the whole section; a prompt that would render broken when blank (a video's address)
     * keeps its rule. The drawer's {@code required=} attributes and {@link #validateRequired} both ask here.
     */
    public boolean isRequired(final Placeholder ph) {
        return ph != null && ph.isRequired() && ph.getType() != Placeholder.Type.RICH_TEXT;
    }

    /**
     * A hand-written template's menu value must be one of its options, and a length-format value a CSS length
     * (2026-11-25), but only a value this save CHANGES: a tone typed by hand before the prompt became a menu
     * ("Dark") keeps saving and rendering exactly as it did, and the drawer offers it as it is.
     */
    private String validateShapes(final ContentInstance instance) {
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null || template.getKind() == TemplateKind.PROGRAMMATIC) {
            return null;
        }
        final ContentInstance stored = instance.getVersion() > 0 ? storedRow(instance.getId()) : null;
        for (final Placeholder ph : template.getPlaceholders()) {
            final String value = instance.getValues().get(ph.getName());
            if (value == null || value.isBlank()
                    || stored != null && value.equals(stored.getValues().get(ph.getName()))) {
                continue;
            }
            if (ph.isFixedChoice() && !ph.getOptions().contains(value)) {
                return Text.get("feedback.contentCommands.notAnOption", promptLabel(ph));
            }
            if (Placeholder.FORMAT_CSS_LENGTH.equals(ph.getFormat()) && !Placeholder.isLength(value)) {
                return Text.get("feedback.contentCommands.notALength", promptLabel(ph));
            }
        }
        return null;
    }

    private static boolean isBlankValue(final Placeholder ph, final String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (ph.getType() != Placeholder.Type.RICH_TEXT) {
            return false;
        }
        final String bare = value.replaceAll("<br\\s*/?>", "").replaceAll("</?p>", "").replace("&nbsp;", " ");
        return bare.isBlank();
    }

    /**
     * The prompt the content dialog shows for a placeholder (2026-11): a rich-text prompt a starter once labeled
     * "Text" or "Body" reads "Content", on every installed version (the installer never rewrites stored rows);
     * every other label is the template's own.
     */
    public String promptLabel(final Placeholder ph) {
        if (ph == null) {
            return "";
        }
        final String label = ph.getLabel();
        return ph.getType() == Placeholder.Type.RICH_TEXT
                && ("Text".equalsIgnoreCase(label) || "Body".equalsIgnoreCase(label))
                ? Text.get("feedback.contentCommands.contentPrompt") : label;
    }

    /** The dialog widget id editing this prompt (the repeat row keeps ids apart), for its label's {@code for}. */
    public String widgetIdFor(final Placeholder ph) {
        if (ph == null) {
            return "phText";
        }
        return switch (ph.getType()) {
            case RICH_TEXT -> "phRich";
            case PERSON -> "phPerson";
            case MULTI_CHOICE -> "phMulti";
            case CHOICE -> "phChoice";
            default -> "phText";
        };
    }

    /**
     * One template as the Add dialog shows it, everything a picker needs without another server call:
     * {@code kindLabel} is "" for a plain section template, "Group" for a container and "Live" for a programmatic
     * type; {@code html} is the template's sample render ({@link #sampleRender}, never blank for a shipped
     * starter); {@code searchText} is the lower-cased name, description and tags a search box filters on;
     * {@code fullWidth} marks a template made for full-width pages (shown, flagged, on a classic one);
     * {@code fields} are the prompts an author will fill in, a required one ending in " *"; {@code holds} says
     * what a group takes ("Feature card (up to 4)"), "" for anything else.
     */
    public record TemplateCard(String id, String name, String description, String kind, String kindLabel,
            String icon, String html, List<String> tags, String searchText, boolean fullWidth, List<String> fields,
            String holds) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** The templates the Add dialog offers for {@code section} ({@link #getTemplateChoicesFor}), as cards. */
    public List<TemplateCard> templateCards(final String section) {
        final Map<String, ContentTemplate> known = knownTemplates();
        final List<TemplateCard> cards = new ArrayList<>();
        for (final ContentTemplate template : getTemplateChoicesFor(section)) {
            cards.add(cardOf(template, known));
        }
        return cards;
    }

    /**
     * {@link #templateCards(String)} for a page whose layout is known: on a CLASSIC page the templates made for
     * full-width pages keep their place in the offer (they render there by design, inside the card) but sort
     * after everything else, so the picker can list them as one trailing group. The host passes its own
     * {@code fullWidth} ui:param: the page decides, since an organization's Editable Pages stay classic whatever
     * its home page's layout is.
     */
    public List<TemplateCard> templateCards(final String section, final boolean pageFullWidth) {
        final List<TemplateCard> cards = templateCards(section);
        if (pageFullWidth) {
            return cards;
        }
        final List<TemplateCard> ordered = new ArrayList<>(cards);
        ordered.sort(Comparator.comparing(TemplateCard::fullWidth));
        return ordered;
    }

    private static List<ContentTemplate> allTemplates() {
        try {
            return DAO.getInstance().getAllTemplates(Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Unable to list templates for the picker's sample renders", ex);
            return List.of();
        }
    }

    private TemplateCard cardOf(final ContentTemplate template, final Map<String, ContentTemplate> known) {
        final TemplateKind kind = template.getKind() == null ? TemplateKind.STANDARD : template.getKind();
        final String icon = switch (kind) {
            case CONTAINER -> "pi-th-large";
            case PROGRAMMATIC -> ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                    .map(ProgrammaticContentTemplate::getIcon).orElse("pi-cog");
            default -> "pi-file-edit";
        };
        // No label for a plain section template: "Section" named both the thing being added and one kind of it.
        final String kindLabel = switch (kind) {
            case CONTAINER -> Text.get("feedback.contentCommands.kindGroup");
            case PROGRAMMATIC -> Text.get("feedback.contentCommands.kindLive");
            default -> "";
        };
        final String description = template.getDescription() == null ? "" : template.getDescription();
        final List<String> tags = List.copyOf(template.getTags());
        return new TemplateCard(template.getId(), template.getName(), description, kind.name(), kindLabel, icon,
                sampleRender(template, known), tags, searchTextOf(template.getName(), description, tags),
                template.isFullWidth(), fieldsOf(template), holdsOf(template, known));
    }

    private static String searchTextOf(final String name, final String description, final List<String> tags) {
        return ((name == null ? "" : name) + " " + description + " " + String.join(" ", tags))
                .toLowerCase(Locale.ROOT).trim();
    }

    /**
     * The prompts an author fills in for a template, as the content dialog would label them, a required one
     * marked " *"; on an organization's site without the shared-site-only ones the dialog hides there.
     */
    private List<String> fieldsOf(final ContentTemplate template) {
        final Set<String> hidden = !SiteContext.current().isOrg() ? Set.of()
                : ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                        .map(ProgrammaticContentTemplate::sharedSiteOnlyProperties).orElse(Set.of());
        final List<String> fields = new ArrayList<>();
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            if (!hidden.contains(ph.getName())) {
                fields.add(promptLabel(ph) + (isRequired(ph) ? " *" : ""));
            }
        }
        return List.copyOf(fields);
    }

    /** What a group template takes: its allowed item templates by name and its limit; "" for any other kind. */
    private static String holdsOf(final ContentTemplate template, final Map<String, ContentTemplate> known) {
        if (template.getKind() != TemplateKind.CONTAINER) {
            return "";
        }
        final List<String> allowed = template.getAllowedChildTemplateIds() == null
                ? List.of() : template.getAllowedChildTemplateIds();
        final String names = allowed.stream()
                .map(known::get)
                .filter(Objects::nonNull)
                .map(ContentTemplate::getName)
                .collect(Collectors.joining(", "));
        if (template.getMaxChildren() == null) {
            return names.isEmpty() ? Text.get("feedback.contentCommands.anyItem") : names;
        }
        final String max = String.valueOf(template.getMaxChildren());
        return names.isEmpty() ? Text.get("feedback.contentCommands.anyItemUpTo", max)
                : Text.get("feedback.contentCommands.itemsUpTo", names, max);
    }

    /** How many sample items a group's sample render lists (fewer when the group holds fewer). */
    private static final int SAMPLE_CHILDREN = 3;
    /**
     * The two wrappers the page puts around a group's child ({@code contentSections.xhtml}: the item's block,
     * then the leaf's), so a row's own selectors match a sample as they match the page.
     */
    private static final String SAMPLE_CHILD_OPEN = "<div class=\"contentItem\"><div>";
    private static final String SAMPLE_CHILD_CLOSE = "</div></div>";

    /**
     * A template as a filled-in section, for the Add dialog's thumbnail and preview, whatever its kind: a
     * STANDARD body rendered with its placeholders' SAMPLE values, a CONTAINER's wrapper and row around a few
     * sample items ({@link #sampleRenderContainer}), a PROGRAMMATIC type's static sample markup
     * ({@code ProgrammaticContentTemplate.getSampleHtml}; a card never runs a live fragment). Nothing is stored
     * and nothing reaches a scope: every instance here is transient.
     */
    public String sampleRender(final ContentTemplate template) {
        return sampleRender(template, null);
    }

    /** {@link #sampleRender(ContentTemplate)} with the other templates already in hand (a group's item lookup). */
    private String sampleRender(final ContentTemplate template, final Map<String, ContentTemplate> known) {
        if (template == null) {
            return "";
        }
        try {
            return switch (template.getKind()) {
                case STANDARD -> sampleRenderStandard(template, template.getName());
                case CONTAINER -> sampleRenderContainer(template, known == null ? knownTemplates() : known);
                case PROGRAMMATIC -> ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                        .map(ProgrammaticContentTemplate::getSampleHtml).orElse("");
                case MAIL -> "";
            };
        } catch (final RuntimeException ex) {
            log.warn("Unable to render a sample of template " + template.getId(), ex);
            return "";
        }
    }

    private static Map<String, ContentTemplate> knownTemplates() {
        final Map<String, ContentTemplate> known = new HashMap<>();
        allTemplates().forEach(template -> known.put(template.getId(), template));
        return known;
    }

    /**
     * A STANDARD template rendered with its placeholders' SAMPLE values: the label where a text prompt has none,
     * and the bundled neutral picture ({@link StarterTemplates#SAMPLE_PICTURE}) where a picture prompt has none,
     * so no card shows an empty frame where its picture goes.
     */
    private static String sampleRenderStandard(final ContentTemplate template, final String title) {
        final ContentTemplate lenient = withSamplePictures(template);
        final ContentInstance sample = blank();
        sample.setTemplateId(template.getId());
        sample.setTemplateVersion(template.getVersion());
        sample.setTitle(title);
        for (final Placeholder ph : template.getPlaceholders()) {
            sample.getValues().put(ph.getName(), sampleValue(ph));
        }
        return ContentRenderer.render(lenient, sample);
    }

    private static String sampleValue(final Placeholder ph) {
        if (ph.getSample() != null) {
            return ph.getSample();
        }
        return switch (ph.getType()) {
            case TEXT, RICH_TEXT -> ph.getLabel();
            case IMAGE_URL -> StarterTemplates.SAMPLE_PICTURE;
            default -> "";
        };
    }

    /**
     * A copy whose sample-less picture prompts are typed as LINKS, for the sample render only. A real picture
     * value must be an absolute http(s) URL ({@code ContentRenderer.requireHttpUrl}); the bundled sample picture
     * is a site-relative path, which only the link rule ({@code requireLinkUrl}) lets through. Both escape the
     * value the same way, and a stored row is never touched.
     */
    private static ContentTemplate withSamplePictures(final ContentTemplate template) {
        return withSamplePictures(template, Set.of());
    }

    /**
     * {@link #withSamplePictures(ContentTemplate)} for the fill-in preview: a picture prompt the author has typed
     * in ({@code typed}) keeps its real type, so the preview refuses exactly the values the page would refuse.
     */
    private static ContentTemplate withSamplePictures(final ContentTemplate template, final Set<String> typed) {
        final ContentTemplate copy = template.copy();
        final List<Placeholder> prompts = new ArrayList<>();
        for (final Placeholder ph : copy.getPlaceholders()) {
            final boolean sampled = ph.getType() == Placeholder.Type.IMAGE_URL && ph.getSample() == null
                    && !typed.contains(ph.getName());
            prompts.add(sampled
                    ? new Placeholder(ph.getName(), Placeholder.Type.URL, ph.getLabel(), ph.getHint(), ph.isRequired())
                    : ph);
        }
        copy.setPlaceholders(prompts);
        return copy;
    }

    /**
     * A CONTAINER template as a filled-in section: its once-around wrapper and its row, exactly as the page
     * emits them ({@code ContentRenderer.containerBody} / {@code renderChildRow} / {@code fillContainerTokens}),
     * around up to {@link #SAMPLE_CHILDREN} sample items (fewer when {@code maxChildren} is lower) built on the
     * first template the group admits. The group's title is the template's name; the items' titles come from
     * {@link StarterTemplates#sampleChildTitles}, since a feature card or a question shows its title.
     */
    String sampleRenderContainer(final ContentTemplate template, final Map<String, ContentTemplate> known) {
        final ContentRenderer.ContainerBody body = ContentRenderer.containerBody(template.getBody());
        final ContentInstance container = blank();
        container.setTitle(template.getName());
        final ContentTemplate item = sampleItemTemplate(template, known);
        final boolean live = item != null && item.getKind() == TemplateKind.PROGRAMMATIC;
        final List<String> titles = StarterTemplates.sampleChildTitles(template.getId());
        final int count = template.getMaxChildren() == null ? SAMPLE_CHILDREN
                : Math.min(SAMPLE_CHILDREN, template.getMaxChildren());
        // Parts joined as they are: every one is markup the renderer produced from a trusted template.
        final List<String> out = new ArrayList<>();
        if (!ContentRenderer.hasContainerTitleSlot(template.getBody())) {
            out.add(ContentRenderer.renderContainerTitle(container.getTitle()));
        }
        out.add(ContentRenderer.fillContainerTokens(body.beforeAll(), container));
        for (int index = 0; item != null && index < count; index++) {
            // A fixed id per place ({{child:id}} is an anchor in some rows): the same sample every time it is asked.
            final ContentInstance child = blank();
            child.setId("sample-" + (index + 1));
            child.setTitle(titles.get(index % titles.size()));
            final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(body.row(), child, live, index);
            out.add(ContentRenderer.fillContainerTokens(row.before(), container));
            out.add(SAMPLE_CHILD_OPEN);
            out.add(live ? sampleRender(item, known) : sampleRenderStandard(item, child.getTitle()));
            out.add(SAMPLE_CHILD_CLOSE);
            out.add(ContentRenderer.fillContainerTokens(row.after(), container));
        }
        out.add(ContentRenderer.fillContainerTokens(body.afterAll(), container));
        return String.join("", out);
    }

    /**
     * The template a group's sample items are built on: the first one its allow-list names that exists and is
     * not itself a group, a STANDARD one before a live one; with no allow-list, the plain text starter. Null
     * when none can be found, and the sample is then the empty group.
     */
    private static ContentTemplate sampleItemTemplate(final ContentTemplate container,
            final Map<String, ContentTemplate> known) {
        final List<String> allowed = container.getAllowedChildTemplateIds() == null
                || container.getAllowedChildTemplateIds().isEmpty()
                        ? List.of(StarterTemplates.TEXT_ONLY_ID) : container.getAllowedChildTemplateIds();
        final List<ContentTemplate> candidates = allowed.stream()
                .map(known::get)
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.getKind() != TemplateKind.CONTAINER)
                .toList();
        return candidates.stream()
                .filter(candidate -> candidate.getKind() == TemplateKind.STANDARD)
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.getFirst());
    }

    /**
     * A transient item of a LIVE template filled with the site's REAL data, for the Add dialog's preview pane,
     * which renders it through the same leaf branches as the page ({@code WEB-INF/contentLeaf.xhtml}): the trip
     * whose page the section is on, else the site's next public trip; its first visible document; the
     * signed-in person. Null when there is nothing real to show (the preview is then the card's static sample,
     * labeled as sample data), for a template that is not a live type, one this site may not use, and a caller
     * who may not edit the section. Never stored and never put in a view: the dialog asks again each request.
     */
    public ContentInstance previewInstance(final String section, final String templateId) {
        if (section == null || section.isBlank() || templateId == null || templateId.isBlank()) {
            return null;
        }
        return RequestMemo.get("previewInstance:" + section + ":" + templateId,
                () -> buildPreviewInstance(section, templateId));
    }

    private ContentInstance buildPreviewInstance(final String section, final String templateId) {
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, section)) {
            return null;
        }
        try {
            final ContentTemplate template = DAO.getInstance().getTemplate(templateId, Cached.NO).orElse(null);
            if (template == null || template.getKind() != TemplateKind.PROGRAMMATIC
                    || (template.isOrgOwned() && !SiteContext.current().isSiteOf(template.getOrgId()))) {
                return null;
            }
            final Map<String, String> values = ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                    .map(type -> type.previewValues(previewContext(caller, section)))
                    .orElse(null);
            return values == null ? null : previewOf(section, template, values);
        } catch (final RuntimeException ex) {
            // The preview is a convenience: the picker falls back to the sample rather than failing with it.
            log.warn("Unable to build a live preview of template " + templateId, ex);
            return null;
        }
    }

    private ProgrammaticContentTemplate.PreviewContext previewContext(final Caller caller, final String section) {
        final Person.Id person = caller.personId();
        return new ProgrammaticContentTemplate.PreviewContext(tripOf(section, containerOf(section)),
                person == null ? null : person.getValue());
    }

    private ContentInstance previewOf(final String section, final ContentTemplate template,
            final Map<String, String> values) {
        final ContentInstance preview = createContent(section, template.getId());
        preview.setTitle(template.getName());
        preview.getValues().putAll(values);
        return preview;
    }

    /** Whether the dialog's working copy is a NEW item: one no save has stored yet. */
    public boolean isNewContent(final ContentInstance instance) {
        return instance != null && instance.getVersion() == 0;
    }

    /** The content drawer's heading: adding or editing, a section of the page or an item of a group. */
    public String drawerTitle(final ContentInstance working, final String section, final String pageKey) {
        final boolean item = section != null && !section.equals(pageKey);
        if (working != null && !isNewContent(working)) {
            return item ? Text.get("feedback.contentCommands.editItem")
                    : Text.get("feedback.contentCommands.editSection");
        }
        return item ? Text.get("feedback.contentCommands.addItem") : Text.get("feedback.contentCommands.addSection");
    }

    /**
     * The in-page preview of a NEW section while its form is filled in (the content drawer redraws it on every
     * field change): the working copy rendered with an EXAMPLE in each prompt the author has not touched yet, so
     * a new section does not collapse to nothing on the first keystroke. A prompt named in {@code touched} (the
     * drawer's comma-separated list) shows exactly what was typed, blank included; a value that is already there
     * always shows. Nothing here is stored: only typed values ever reach a save.
     *
     * <p>"" whenever the page's own render is the right preview instead ({@code contentLeaf.xhtml} over
     * {@link #liveInstance}): an item that already exists, the "Hide example text" switch ({@code plain}), and
     * any kind but STANDARD.
     */
    public String liveHtml(final ContentInstance working, final String touched, final String plain) {
        final ContentTemplate template = showsExamples(working, plain) ? resolveTemplate(working) : null;
        if (template == null || template.getKind() != TemplateKind.STANDARD) {
            return "";
        }
        final Set<String> typed = namesOf(touched);
        final ContentInstance filled = working.copy();
        for (final Placeholder ph : template.getPlaceholders()) {
            if (isUntouched(filled, ph.getName(), typed)) {
                filled.getValues().put(ph.getName(), sampleValue(ph));
            }
        }
        try {
            return ContentRenderer.render(withSamplePictures(template, typed), filled);
        } catch (final RuntimeException ex) {
            log.warn("Unable to render the fill-in preview of template " + template.getId(), ex);
            return "";
        }
    }

    /**
     * The item {@code contentLeaf.xhtml} renders as the in-page preview: the working copy itself, except that a
     * NEW live section borrows the site's real preview values ({@link #previewInstance}) for the choices the
     * author has not made yet, so it shows the site's own trip or document from the start. The copy is transient
     * and the working copy is never changed by it.
     */
    public ContentInstance liveInstance(final ContentInstance working, final String touched, final String plain) {
        final ContentTemplate template = showsExamples(working, plain) ? resolveTemplate(working) : null;
        if (template == null || template.getKind() != TemplateKind.PROGRAMMATIC) {
            return working;
        }
        final ContentInstance real = previewInstance(working.getSection(), working.getTemplateId());
        if (real == null) {
            return working;
        }
        final Set<String> typed = namesOf(touched);
        final ContentInstance filled = working.copy();
        for (final Map.Entry<String, String> value : real.getValues().entrySet()) {
            if (isUntouched(filled, value.getKey(), typed)) {
                filled.getValues().put(value.getKey(), value.getValue());
            }
        }
        if (hasNoText(filled.getTitle())) {
            filled.setTitle(real.getTitle());
        }
        return filled;
    }

    private boolean showsExamples(final ContentInstance working, final String plain) {
        return isNewContent(working) && hasNoText(plain);
    }

    private static boolean isUntouched(final ContentInstance instance, final String name, final Set<String> typed) {
        return !typed.contains(name) && hasNoText(instance.getValues().get(name));
    }

    private static boolean hasNoText(final String value) {
        return value == null || value.isBlank();
    }

    /** The prompt names in the drawer's comma-separated {@code touched} list. */
    private static Set<String> namesOf(final String csv) {
        final Set<String> names = new HashSet<>();
        if (csv != null) {
            for (final String name : csv.split(",")) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * Whether the Title the form asks for shows nowhere on the page: a plain section template placed straight on
     * a page has no slot for it (a group shows its title, a group's row shows its item's, and a live type titles
     * itself). The title still names the section in Arrange sections and in History, which the form then says.
     * On a trip page its strip of section links is made of the sections' titles, so there it is unseen only for
     * a section left out of those links ({@link ContentInstance#isShownInLinks}).
     */
    public boolean isTitleUnseen(final ContentInstance working, final String pageKey) {
        return working != null && pageKey != null && pageKey.equals(working.getSection())
                && (OrgPageBootstrap.tripOf(pageKey) == null || !working.isShownInLinks())
                && TemplateKind.STANDARD.name().equals(getKind(working));
    }

    /**
     * Whether the drawer offers "Show in the page's links" (2026-11-25): a section placed straight on a TRIP page,
     * the only page with a strip of section links. A group's item never has a link of its own.
     */
    public boolean offersLinksChoice(final ContentInstance working, final String pageKey) {
        return working != null && pageKey != null && pageKey.equals(working.getSection())
                && OrgPageBootstrap.tripOf(pageKey) != null;
    }

    /**
     * The name of the template an item is built on, the version it renders with (Stay on Latest followed), for
     * the drawer's "Template:" line (2026-11-25, so editors learn which templates there are). "" when the item
     * or its template is gone.
     */
    public String templateName(final ContentInstance instance) {
        final ContentTemplate template = instance == null ? null : resolveTemplate(instance);
        return template == null || template.getName() == null ? "" : template.getName();
    }

    /** The id of the template an item is built on, for the drawer's "Edit this template" link; "" without one. */
    public String templateIdOf(final ContentInstance instance) {
        return instance == null || instance.getTemplateId() == null ? "" : instance.getTemplateId();
    }

    /**
     * How a section with no title is named where sections are listed (Arrange sections, "Adding above ..."):
     * its template's name and the first words of its text, e.g. 'Opening banner: "A weekend that changes..."'.
     * A title is optional since 2026-11-25, so a bare id or "untitled" would leave a list of look-alike rows.
     * "" when even that says nothing.
     */
    String untitledLabel(final ContentInstance row) {
        final ContentTemplate template = row == null ? null : resolveTemplate(row);
        if (template == null) {
            return "";
        }
        final String name = template.getName() == null ? "" : template.getName().trim();
        final String words = firstWords(template, row);
        if (words.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? words : Text.get("feedback.contentCommands.untitledLabel", name, words);
    }

    /** The first few words of the first text an item holds, in its template's prompt order, as plain text. */
    private static String firstWords(final ContentTemplate template, final ContentInstance row) {
        for (final Placeholder ph : template.getPlaceholders()) {
            final String value = row.getValues().get(ph.getName());
            if ((ph.getType() == Placeholder.Type.TEXT || ph.getType() == Placeholder.Type.RICH_TEXT)
                    && ph.getFormat() == null && value != null && !value.isBlank()) {
                final String plain = value.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").trim();
                final String[] split = plain.split("\\s+");
                if (!plain.isEmpty()) {
                    return split.length <= FIRST_WORDS ? plain.replaceAll("\\s+", " ")
                            : String.join(" ", List.of(split).subList(0, FIRST_WORDS)) + "...";
                }
            }
        }
        return "";
    }

    /** How many words {@link #untitledLabel} quotes. */
    private static final int FIRST_WORDS = 6;

    /**
     * Where the Add drawer's new item will go, in the author's words: above a named section of the page, at the
     * end of the page, or inside the group it was opened from. "" with no target at all.
     */
    public String insertLabel(final String section, final String pageKey, final String beforeId) {
        if (section == null || section.isBlank()) {
            return "";
        }
        if (!section.equals(pageKey)) {
            final String group = plainTitleOf(section);
            return group.isEmpty() ? Text.get("feedback.contentCommands.addingToThisGroup")
                    : Text.get("feedback.contentCommands.addingToGroup", group);
        }
        if (beforeId == null || !idsFor(section, true).contains(beforeId)) {
            return Text.get("feedback.contentCommands.addingAtEnd");
        }
        final String titled = plainTitleOf(beforeId);
        final String next = titled.isEmpty() ? untitledLabel(storedRow(beforeId)) : titled;
        return next.isEmpty() ? Text.get("feedback.contentCommands.addingAboveUntitled")
                : Text.get("feedback.contentCommands.addingAbove", next);
    }

    /** A stored item's title as plain text (a title may carry markup); "" when there is none or no such item. */
    private static String plainTitleOf(final String id) {
        try {
            final String title = DAO.getInstance().getContent(id, Cached.NO).map(ContentInstance::getTitle).orElse("");
            return title == null ? "" : title.replaceAll("<[^>]*>", "").trim();
        } catch (final RuntimeException ex) {
            log.warn("Unable to read the title of content " + id, ex);
            return "";
        }
    }

    /**
     * The Add drawer's "move it up / down the page": the id the new section will go in front of after one step
     * from {@code beforeId} ("" or an id that is not on the page means the end), "" for the end of the page.
     */
    public String moveInsertPoint(final String pageKey, final String beforeId, final boolean up) {
        final List<String> ids = idsFor(pageKey, true);
        final int at = beforeId == null || !ids.contains(beforeId) ? ids.size() : ids.indexOf(beforeId);
        final int next = Math.max(0, Math.min(ids.size(), at + (up ? -1 : 1)));
        return next >= ids.size() ? "" : ids.get(next);
    }

    /** The title (when it carries markup) and every RICH_TEXT value must be structurally sound HTML. */
    private String validateAuthoredHtml(final ContentInstance instance) {
        if (PageHtml.titleHasMarkup(instance.getTitle())) {
            final String titleProblem = HtmlCheck.structureProblem(instance.getTitle());
            if (titleProblem != null) {
                return Text.get("common.title") + ": " + titleProblem;
            }
        }
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null) {
            return null;
        }
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            if (ph.getType() != Placeholder.Type.RICH_TEXT) {
                continue;
            }
            final String problem = HtmlCheck.structureProblem(instance.getValues().get(ph.getName()));
            if (problem != null) {
                return ph.getLabel() + ": " + problem;
            }
        }
        return null;
    }

    /**
     * Every PERSON value must name a real person, and on an organization's page a MEMBER of that
     * organization: the picker only offers members (see {@code PersonCommands.completeForContent}), but the
     * value arrives as a plain posted string, so the tenancy boundary is enforced here where nothing can
     * forge around it. Blank is allowed; a required PERSON prompt is the type's concern, not this check's.
     */
    private String validatePersonValues(final ContentInstance instance) {
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null) {
            return null;
        }
        for (final Placeholder ph : ProgrammaticTypes.placeholdersOf(template)) {
            final String value = ph.getType() == Placeholder.Type.PERSON
                    ? instance.getValues().get(ph.getName()) : null;
            if (value != null && !value.isBlank()) {
                final String problem = validatePerson(instance.getSection(), value.trim());
                if (problem != null) {
                    return ph.getLabel() + ": " + problem;
                }
            }
        }
        return null;
    }

    private String validatePerson(final String section, final String personId) {
        final Person person;
        try {
            person = DAO.getInstance().getPerson(Person.Id.from(personId), Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the person picked for content: " + personId, ex);
            return Text.get("feedback.contentCommands.personUnchecked");
        }
        if (person == null) {
            return Text.get("feedback.contentCommands.noSuchPerson");
        }
        final Organization.Id org = orgOf(section, containerOf(section));
        if (org != null && !isMember(org, person.getId())) {
            return Text.get("feedback.contentCommands.personNotMember");
        }
        return null;
    }

    /** Membership from org_members, the source of truth (Person.orgIds is only its derived edge). */
    private static boolean isMember(final Organization.Id org, final Person.Id personId) {
        return DAO.getInstance().getOrgMembers(org, Cached.NO).stream()
                .anyMatch(member -> personId.equals(member.getPersonId()));
    }

    /**
     * The people a PERSON prompt may offer, for the content dialog's autocomplete. Authorization lives HERE,
     * on the data source, because the completion endpoint is POSTable without ever opening the dialog: the
     * section being edited (the dialog's {@code viewScope.editSection}) must be one the caller may edit, and
     * an organization's page offers only that organization's members, whoever is asking -- the tenancy
     * boundary {@link #validatePersonValues} enforces again on save. Anywhere else (the shared and marketing
     * pages) only a site admin or global contentAdmin may search everyone.
     */
    public List<Person> completeForContent(final String query) {
        final Object section = ScopeUtil.getInstance().getViewMap("editSection");
        return completeForContent(section == null ? null : section.toString(), query);
    }

    /** {@link #completeForContent(String)} for an explicit section (the testable half). */
    List<Person> completeForContent(final String section, final String query) {
        final Caller caller = callerSource.get();
        if (section == null || section.isBlank() || !mayEdit(caller, section)) {
            return List.of();
        }
        final Organization.Id org = orgOf(section, containerOf(section));
        if (org != null) {
            // The scoping is HERE, the organization's members and nobody else; the picker only narrows them.
            return PeoplePicker.pick(DAO.getInstance().getOrgMembers(org, Cached.YES).stream()
                    .map(member -> DAO.getInstance().getPerson(member.getPersonId(), Cached.YES))
                    .flatMap(Optional::stream).toList(), query, PERSON_PICKER_LIMIT);
        }
        if (!caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            return List.of();
        }
        try {
            return DAO.getInstance().searchPeople(query, PERSON_PICKER_LIMIT, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to search people for '{}'", query, ex);
            return List.of();
        }
    }

    /**
     * What a {@code person} section shows ({@code ptypes/person.xhtml}): the instance's own words plus the
     * named account's live, privacy-filtered fields (see {@link PersonType}). Memoized per request, because
     * the fragment reads a dozen properties of one card through a lazily-evaluated ui:param. The memo is keyed
     * on the VALUES as well as the row's id and version: the edit view renders the unsaved working copy through
     * this same fragment, and its values change while its id and version stand still.
     */
    public PersonType.Card personCard(final ContentInstance instance, final boolean signedIn) {
        if (instance == null) {
            return PersonType.card(Map.of(), null, signedIn);
        }
        return RequestMemo.get("personCard:" + instance.getId() + ":" + instance.getVersion() + ":"
                        + instance.getValues().hashCode() + ":" + signedIn,
                () -> PersonType.card(instance.getValues(), cardPerson(instance), signedIn));
    }

    /** The account a card names, or null for none (blank id, or one that no longer exists). */
    private static Person cardPerson(final ContentInstance instance) {
        final String id = instance.getValues().get(PersonType.PROP_PERSON_ID);
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return DAO.getInstance().getPerson(Person.Id.from(id.trim()), Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the person a content card names: " + id, ex);
            return null;
        }
    }

    /** The container rules: kind, allow-list, and head count, read from the container's CURRENT template. */
    private String validateAsChild(final ContentInstance child, final ContentInstance parent) {
        final ContentTemplate childTemplate = resolveTemplate(child);
        if (childTemplate != null && childTemplate.getKind() == TemplateKind.CONTAINER) {
            return Text.get("feedback.contentCommands.containerInContainer");
        }
        final ContentTemplate containerTemplate =
                DAO.getInstance().getTemplate(parent.getTemplateId(), Cached.NO).orElse(null);
        if (containerTemplate == null || containerTemplate.getKind() != TemplateKind.CONTAINER) {
            return null;    // parent id collides with a non-container instance; nothing to enforce
        }
        final List<String> allowed = effectiveAllowList(parent, containerTemplate);
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(child.getTemplateId())) {
            return Text.get("feedback.contentCommands.notAllowedHere", child.getTemplateId());
        }
        final Integer max = containerTemplate.getMaxChildren();
        if (max != null) {
            final long others = readSection(parent.getId()).stream()
                    .filter(sibling -> !sibling.getId().equals(child.getId()))
                    .count();
            if (others >= max) {
                return Text.get("feedback.contentCommands.containerFull", String.valueOf(max));
            }
        }
        return null;
    }

    /** A container INSTANCE's allow-list (when non-empty) takes precedence over its template's. */
    private static List<String> effectiveAllowList(final ContentInstance container,
            final ContentTemplate containerTemplate) {
        final List<String> own = container.getAllowedChildTemplateIds();
        if (own != null && !own.isEmpty()) {
            return own;
        }
        return containerTemplate == null ? null : containerTemplate.getAllowedChildTemplateIds();
    }

    /**
     * The layout body a live trip section draws (docs/content-templates.md, "Live sections with a layout"), or ""
     * for the built-in look. Always the template's CURRENT version, whatever version the row was saved against:
     * a layout edit reaches every trip page with no row touched, through the same organization guard a Stay on
     * Latest row follows by ({@link #mayFollow}). Only a programmatic template carries one. Cached reads only, so
     * a failed read draws the built-in look rather than failing the page.
     */
    public String layoutBody(final ContentInstance instance) {
        if (instance == null || instance.getTemplateId() == null) {
            return "";
        }
        final ContentInstance following = instance.copy();
        following.setStayOnLatest(true);
        try {
            final ContentTemplate template = resolveTemplate(following);
            return template == null || template.getKind() != TemplateKind.PROGRAMMATIC || template.getBody() == null
                    ? "" : template.getBody();
        } catch (final RuntimeException ex) {
            log.warn("Unable to read the layout of section " + instance.getId(), ex);
            return "";
        }
    }

    /**
     * The template an instance renders with: a Stay on Latest instance's CURRENT version, when it may follow it
     * ({@link #mayFollow}); otherwise, and whenever that finds nothing, {@link #resolvePinned}.
     */
    private ContentTemplate resolveTemplate(final ContentInstance instance) {
        if (instance.getTemplateId() == null) {
            return null;
        }
        final ContentTemplate saved = savedAgainst(instance, Cached.YES);
        if (instance.isStayOnLatest()) {
            final ContentTemplate latest = currentTemplate(instance.getTemplateId(), Cached.YES);
            if (latest != null && mayFollow(saved, latest)) {
                return latest;
            }
        }
        return saved != null ? saved : guardedFallback(instance);
    }

    /**
     * The version the instance's VALUES were saved against ({@code templateVersion}), falling back to the
     * template's latest when that version has aged out of retention (only where {@link #mayFollow} allows), and to
     * null when the template is gone.
     */
    private ContentTemplate resolvePinned(final ContentInstance instance) {
        if (instance.getTemplateId() == null) {
            return null;
        }
        final ContentTemplate saved = savedAgainst(instance, Cached.YES);
        return saved != null ? saved : guardedFallback(instance);
    }

    /**
     * An instance whose saved-against version aged out of retention: the template's current version when
     * {@link #mayFollow} lets it (that version's organization being unknown, one the requesting SITE may use: a
     * shared template, or the site's own organization's), else the newest retained version the site may use
     * ({@link #newestRetainedUsable}), else null. A published block must not go blank just because its template's
     * newest version moved to an organization.
     */
    private static ContentTemplate guardedFallback(final ContentInstance instance) {
        final ContentTemplate latest = currentTemplate(instance.getTemplateId(), Cached.YES);
        if (latest == null || mayFollow(null, latest)) {
            return latest;
        }
        return newestRetainedUsable(latest);
    }

    /**
     * The newest retained version older than {@code latest} that the requesting site may use ({@link #mayFollow}
     * with no saved-against version), or null. Walks down the versioned cache fields ({@code id#vN}, the same cached
     * lookup a pinned instance renders with, so a warm page view stays at zero DynamoDB reads) and stops at the
     * first version that is gone: retention keeps a contiguous run of the newest versions, so nothing older is
     * left. Rare (an aged-out instance of a template re-scoped to an organization), and bounded by the retention
     * setting's ceiling.
     */
    private static ContentTemplate newestRetainedUsable(final ContentTemplate latest) {
        final int oldest = Math.max(1, latest.getVersion() - MAX_RETAINED_VERSIONS);
        for (int version = latest.getVersion() - 1; version >= oldest; version--) {
            final ContentTemplate candidate = loadTemplateVersion(latest.getId(), version, Cached.YES);
            if (candidate == null) {
                return null;
            }
            if (mayFollow(null, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether an instance whose values were saved against {@code saved} may render (or be moved to) {@code other},
     * another version of the same template. Who may use a template is decided when content is ADDED
     * ({@link #getTemplateChoicesFor}); a template whose scope later moved to another organization must not carry
     * that organization's markup onto pages it was never offered to. So while the saved-against version is
     * retained both must belong to the same organization (or both be shared). Once it has aged out its organization
     * is unknown, and the Add dialog's own rule decides: a shared version, or one the requesting site's
     * organization owns ({@link SiteContext#isSiteOf}), so an organization's own template keeps rendering on its
     * own site and nowhere else.
     */
    private static boolean mayFollow(final ContentTemplate saved, final ContentTemplate other) {
        if (saved != null) {
            return Objects.equals(orgIdOf(saved), orgIdOf(other));
        }
        return isShared(other) || SiteContext.current().isSiteOf(other.getOrgId());
    }

    /** A site-level template every site may use (no owning organization). */
    private static boolean isShared(final ContentTemplate template) {
        return !template.isOrgOwned();
    }

    private static String orgIdOf(final ContentTemplate template) {
        return isShared(template) ? null : template.getOrgId();
    }

    /**
     * A template's current version: its cached latest field, or, when that field is missing (the first rebuild
     * after this build is deployed, a template written behind the app's back), the row-backed unversioned lookup
     * the render path used before the field existed. Null when the template is gone or the read failed.
     */
    private static ContentTemplate currentTemplate(final String templateId, final Cached cached) {
        try {
            return DAO.getInstance().getLatestTemplate(templateId, cached)
                    .or(() -> DAO.getInstance().getTemplate(templateId, cached))
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the latest version of template " + templateId, ex);
            return null;
        }
    }

    /**
     * Carries a Stay on Latest instance's values from the version they were saved against to the template's
     * CURRENT version, IN PLACE ({@link TemplateValueMigrator}'s rules, the version menu's), and moves its
     * {@code templateVersion} along. A no-op for a pinned instance, one already on (or, from a lagging cache,
     * apparently past) the current version, one whose template is gone, and one {@link #mayFollow} keeps pinned:
     * values are never carried backward or across organizations. When the saved-against version has aged out of
     * retention only same-named values carry, the same pairing a pinned instance gets from its fallback.
     *
     * <p>Callers decide whether this is ever written: the render path applies it to the fresh objects every
     * section read deserializes and never saves them; only {@link #saveContent} stores the result, and it and the
     * dialog's open ({@link #getContent}) say what moved ({@link #reportCarry}).
     *
     * @return what moved and what was dropped, or null when nothing changed.
     */
    private TemplateValueMigrator.Result followLatest(final ContentInstance instance, final Cached cached) {
        if (instance == null || !instance.isStayOnLatest() || instance.getTemplateId() == null) {
            return null;
        }
        final ContentTemplate latest = currentTemplate(instance.getTemplateId(), cached);
        if (latest == null || latest.getVersion() <= instance.getTemplateVersion()) {
            return null;
        }
        final ContentTemplate saved = savedAgainst(instance, cached);
        if (!mayFollow(saved, latest)) {
            return null;
        }
        final List<Placeholder> to = ProgrammaticTypes.placeholdersOf(latest);
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                saved == null ? to : ProgrammaticTypes.placeholdersOf(saved), to, instance.getValues());
        instance.setTemplateVersion(latest.getVersion());
        instance.setValues(result.values());
        return result;
    }

    /** {@link #followLatest} as a mapping step ({@link #getContent}): the instance carried forward, and said so. */
    private ContentInstance followingLatest(final ContentInstance instance) {
        reportCarry(instance, followLatest(instance, Cached.NO), true);
        return instance;
    }

    /**
     * Tells the editor what carrying a Stay on Latest item forward did to its values, when it renamed or dropped
     * any: {@link TemplateValueMigrator}'s contract, since a value lost silently on open is lost for good on the
     * next Apply. Silent when every value carried unchanged, and on the render path, which never calls this.
     * {@code cancelUndoes}: whether the dialog's Cancel still keeps the stored values (on open; not after a save).
     */
    private static void reportCarry(final ContentInstance instance, final TemplateValueMigrator.Result result,
            final boolean cancelUndoes) {
        if (result != null && (!result.renamed().isEmpty() || !result.dropped().isEmpty())) {
            PageFeedback.warn(Text.get("feedback.contentCommands.followsLatest",
                    String.valueOf(instance.getTemplateVersion()), migrationSummary(result, cancelUndoes)));
        }
    }

    /** The exact version an instance's values were saved against, or null when it is no longer retained. */
    private static ContentTemplate savedAgainst(final ContentInstance instance, final Cached cached) {
        return loadTemplateVersion(instance.getTemplateId(), instance.getTemplateVersion(), cached);
    }

    /**
     * Deletes an instance (all versions); refused without edit rights on its section. Deleting a CONTAINER
     * instance cascades to its children first ({@link #deleteWithChildren}) -- each through the DAO's surgical
     * single-row delete, never a cache invalidation.
     */
    public boolean deleteContent(final String id) {
        final ContentInstance existing;
        try {
            existing = DAO.getInstance().getContent(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up content for delete: " + id, ex);
            return false;
        }
        if (existing == null) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, existing.getSection())) {
            log.warn("Refusing content delete in '{}': caller lacks edit rights", existing.getSection());
            return false;
        }
        try {
            return deleteWithChildren(caller, existing);
        } catch (final RuntimeException ex) {
            log.error("Unable to delete content: " + id, ex);
            return false;
        }
    }

    /**
     * THE delete cascade, ungated (callers check their own right first): the row's children, if it has any (a
     * container's), then the row itself, each through the DAO's surgical single-row delete and each with its
     * own audit line, so the trail names every row that was destroyed. Shared with
     * {@code SitePageCommands.deletePage}, which deletes a whole page this way. Children are read uncached and
     * for every row, not only a CONTAINER's: a row whose template no longer resolves must not leave its
     * children behind.
     *
     * @return whether the row and all of its children were deleted
     */
    static boolean deleteWithChildren(final Caller caller, final ContentInstance row) {
        boolean all = true;
        for (final ContentInstance child : DAO.getInstance().getContentForSection(row.getId(), Cached.NO)) {
            final boolean gone = Boolean.TRUE.equals(DAO.getInstance().deleteContent(child.getId()));
            if (gone) {
                audit(caller, child.getId(), "Deleted '" + child.getTitle()
                        + "' with its container '" + row.getTitle() + "'");
            }
            all &= gone;
        }
        final boolean deleted = Boolean.TRUE.equals(DAO.getInstance().deleteContent(row.getId()));
        if (deleted) {
            audit(caller, row.getId(), "Deleted '" + row.getTitle() + "' from " + row.getSection());
        }
        return all && deleted;
    }

    /**
     * Starts a language version of a page from a copy of another version of the SAME page
     * ({@code content/PageVersions}): every top-level row and every container's children, each a new row with
     * a new id and its own history from version 1. Refused unless the caller may edit the target, the two keys
     * are versions of one page, and the target is still empty: copying over written content would silently
     * double it. Rows keep their template and pinned version; nothing links the copy back to its source.
     *
     * @return whether every row was copied
     */
    public boolean copyPage(final String fromKey, final String toKey) {
        final Caller caller = callerSource.get();
        if (fromKey == null || toKey == null || fromKey.equals(toKey)
                || !PageVersions.baseOf(fromKey).equals(PageVersions.baseOf(toKey))
                || !mayEdit(caller, toKey) || !readSection(toKey).isEmpty()) {
            log.warn("Refusing page copy from '{}' to '{}'", fromKey, toKey);
            return false;
        }
        boolean all = true;
        try {
            for (final ContentInstance row : DAO.getInstance().getContentForSection(fromKey, Cached.NO)) {
                all &= copyRow(caller, row, toKey, fromKey, true);
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to copy page " + fromKey + " to " + toKey, ex);
            return false;
        }
        return all;
    }

    /**
     * One row and, beneath it, its children, into {@code section}; the children's section is the NEW row's id.
     * {@code keepGrants} as {@link #copyOf} takes it: true for a language version of the same page, whose groups keep
     * the editors who already edit that page.
     */
    private boolean copyRow(final Caller caller, final ContentInstance row, final String section,
            final String copiedFrom, final boolean keepGrants) {
        final ContentInstance copy = copyOf(caller, row, UUID.randomUUID().toString(), section, row.getPosition(),
                new HashMap<>(row.getValues()), keepGrants);
        if (!storeContent(copy, retainCount())) {
            return false;
        }
        audit(caller, copy.getId(), "Copied '" + copy.getTitle() + "' into " + section + " from " + copiedFrom);
        return copyChildren(caller, row, copy, keepGrants);
    }

    /** The children of {@code row}, each copied (with its own children) under {@code copy}. */
    private boolean copyChildren(final Caller caller, final ContentInstance row, final ContentInstance copy,
            final boolean keepGrants) {
        boolean all = true;
        for (final ContentInstance child : DAO.getInstance().getContentForSection(row.getId(), Cached.NO)) {
            all &= copyRow(caller, child, copy.getId(), row.getId(), keepGrants);
        }
        return all;
    }

    /**
     * THE row-copy recipe (the Clone button, a page's language version, another trip's page through
     * {@code TripPageCommands.clonePage}): a new, unsaved row holding what {@code row} holds, under {@code id} in
     * {@code section} at {@code position}, with {@code values}. A seeded placeholder's copy keeps the seed as its
     * author (with the seed's "Show until"), so it stays hidden from visitors and its first save publishes it,
     * exactly as the original's would ({@link #publishIfSeededPlaceholder}). A group's editor privileges are a
     * contentAdmin's grant ({@link #guardContainerConfig}): a copy that reaches beyond the row's own page carries them
     * only when a contentAdmin makes it ({@code keepGrants} false), never otherwise, as anyone else's own save of
     * the copy would drop them.
     */
    static ContentInstance copyOf(final Caller caller, final ContentInstance row, final String id,
            final String section, final int position, final Map<String, String> values, final boolean keepGrants) {
        final String author = OrgPageBootstrap.isSeededPlaceholder(row) ? row.getModifiedBy()
                : caller.auditActor().email();
        final boolean grants = row.getEditorPrivileges() != null
                && (keepGrants || caller.has(PrivilegeCommands.CONTENT_ADMIN));
        return new ContentInstance(id, section, row.getTitle(),
                row.getTemplateId(), row.getTemplateVersion(), values, row.getEventDate(),
                position, 0, null, author,
                grants ? new ArrayList<>(row.getEditorPrivileges()) : null,
                row.getAllowedChildTemplateIds() == null ? null : new ArrayList<>(row.getAllowedChildTemplateIds()),
                row.isStayOnLatest(), row.getInLinks());
    }

    /**
     * The section toolbar's Clone (2026-11-25): a copy of one section or group item, with a group's items copied
     * beneath it, saved right AFTER the original, which keeps its place. A group's rules still apply to an item's
     * copy (its allow-list and its size, {@link #validateAsChild}), but required prompts are not asked again: the
     * copy holds exactly what is already stored, and a seeded placeholder holds blanks on purpose. The new row's
     * history starts at version 1 and its audit line names the original.
     *
     * @return the copy's id, or "" when nothing was copied (the reason is on the page)
     */
    public String cloneContent(final String id) {
        final ContentInstance original = id == null ? null : storedRow(id);
        final Caller caller = callerSource.get();
        if (original == null || !mayEdit(caller, original.getSection())) {
            log.warn("Refusing clone of '{}'", id);
            PageFeedback.error(Text.get("feedback.contentCommands.cloneFailed"));
            return "";
        }
        final ArrayList<String> order = idsFor(original.getSection(), true);
        final int at = order.indexOf(original.getId()) + 1;
        final ContentInstance copy = copyOf(caller, original, UUID.randomUUID().toString(), original.getSection(),
                at > 0 ? at : order.size(), new HashMap<>(original.getValues()), false);
        final ContentInstance parent = storedRow(original.getSection());
        final String problem = parent == null ? null : validateAsChild(copy, parent);
        if (problem != null) {
            PageFeedback.error(Text.get("feedback.shared.notSaved"), problem);
            return "";
        }
        try {
            if (!storeContent(copy, retainCount())) {
                PageFeedback.error(Text.get("feedback.contentCommands.cloneFailed"));
                return "";
            }
            audit(caller, copy.getId(), "Cloned '" + copy.getTitle() + "' in " + copy.getSection() + " from "
                    + original.getId());
            if (!copyChildren(caller, original, copy, false)) {
                PageFeedback.warn(Text.get("feedback.contentCommands.cloneChildrenFailed"));
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to clone content: " + id, ex);
            PageFeedback.error(Text.get("feedback.contentCommands.cloneFailed"));
            return "";
        }
        if (at > 0) {
            order.add(at, copy.getId());
            if (!applyOrder(copy.getSection(), order)) {
                PageFeedback.warn(Text.get("feedback.contentCommands.savedNotPlaced"));
            }
        }
        return copy.getId();
    }

    /** Whether {@link #cloneContent} made a copy (a JSFT {@code if} reads a bean call, never {@code not empty}). */
    public boolean wasCloned(final String copyId) {
        return copyId != null && !copyId.isEmpty();
    }

    /**
     * The anchor to open the page on after {@link #cloneContent}: the copy itself on a page, or its group for an
     * item inside one (the anchors are the page sections'), as {@link #landingAnchor} answers for an Add. Decided
     * from the ORIGINAL, which sits where the copy does: a read of the copy right after its write may be stale.
     */
    public String cloneLanding(final String copyId, final String originalId, final String pageKey) {
        if (copyId == null || copyId.isEmpty() || originalId == null || pageKey == null) {
            return "";
        }
        final ContentInstance original = storedRow(originalId);
        if (original == null) {
            return "";
        }
        return pageKey.equals(original.getSection()) ? copyId : original.getSection();
    }

    /**
     * Deletes a whole language version of a page: every row in it, each through {@link #deleteWithChildren} so
     * the trail names every row. Only a VERSION key is accepted: a page's own key is the one every reader falls
     * back to, and removing it is the Editable Pages page's job ({@code SitePageCommands.deletePage}), not this.
     *
     * @return whether every row was deleted
     */
    public boolean deleteVersion(final String versionKey) {
        final Caller caller = callerSource.get();
        if (PageVersions.languageOf(versionKey).isEmpty() || !mayEdit(caller, versionKey)) {
            log.warn("Refusing to delete page version '{}'", versionKey);
            return false;
        }
        boolean all = true;
        try {
            for (final ContentInstance row : DAO.getInstance().getContentForSection(versionKey, Cached.NO)) {
                all &= deleteWithChildren(caller, row);
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to delete page version " + versionKey, ex);
            return false;
        }
        return all;
    }

    /**
     * Applies the arrange dialog's new order to a section (a page key or a container id): each listed id
     * gets its list index as position. Version-silent by design -- see {@code ContentDAO.reorderContent}.
     */
    public boolean applyOrder(final String section, final List<String> orderedIds) {
        if (section == null || section.isBlank() || orderedIds == null || orderedIds.isEmpty()) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, section)) {
            log.warn("Refusing reorder of '{}': caller lacks edit rights", section);
            return false;
        }
        log.debug("applyOrder '{}': {}", section, orderedIds);
        final boolean applied;
        try {
            applied = DAO.getInstance().reorderContent(section, orderedIds);
        } catch (final RuntimeException ex) {
            log.error("Unable to reorder section: " + section, ex);
            return false;
        }
        if (applied) {
            audit(caller, section, "Reordered '" + section + "' (" + orderedIds.size() + " items)");
        }
        return applied;
    }

    /**
     * Every retained version of an instance, current first, for the history dialog; nothing for a caller who
     * may not edit the instance's section. The History button renders behind {@link #canEdit}, but the id
     * rides the postback, and old versions hold drafts and expired text the page no longer shows anyone:
     * the rule {@link #restoreContent} applies is asked again here (unlike
     * {@code TemplateCommands.getHistory}: templates are sharable by id, content history is not).
     */
    public List<ContentInstance> getHistory(final String id) {
        final Caller caller = callerSource.get();
        try {
            return DAO.getInstance().getContentRecord(id, Cached.NO)
                    .filter(record -> record.getCurrent() != null
                            && mayEdit(caller, record.getCurrent().getSection()))
                    .map(ContentRecord::getAllVersions).orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to load content history: " + id, ex);
            return List.of();
        }
    }

    /** Restores a retained version as the new current (itself a new version -- history stays linear). */
    public boolean restoreContent(final String id, final int version) {
        final ContentRecord record;
        try {
            record = DAO.getInstance().getContentRecord(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to load content for restore: " + id, ex);
            return false;
        }
        final ContentInstance snapshot = record == null ? null : record.findVersion(version);
        if (snapshot == null || record.getCurrent() == null) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, snapshot.getSection())) {
            log.warn("Refusing content restore in '{}': caller lacks edit rights", snapshot.getSection());
            return false;
        }
        final ContentInstance restored = snapshot.copy();
        // Carry the stored current version so the DAO's lost-update guard accepts the save.
        restored.setVersion(record.getCurrent().getVersion());
        if (OrgPageBootstrap.isSeededPlaceholder(snapshot)) {
            // Restoring the seeded version of a published placeholder must never hide the section again.
            restored.setEventDate(null);
        }
        restored.setModifiedBy(caller.auditActor().email());
        // A version stored before the allowlist existed comes back through it, like anything saved today.
        final List<String> removed = cleanAuthoredHtml(restored);
        final boolean saved;
        try {
            saved = storeContent(restored, retainCount());
        } catch (final RuntimeException ex) {
            log.error("Unable to restore content: " + id, ex);
            return false;
        }
        if (saved) {
            warnOfRemovals(removed);
            audit(caller, id, "Restored '" + restored.getTitle() + "' to v" + version
                    + " (saved as v" + restored.getVersion() + ")");
        }
        return saved;
    }

    /**
     * The rendered HTML for one STANDARD instance: its pinned template version, falling back to the
     * template's latest when that version has aged out of retention, and to nothing when the template is
     * gone entirely. CONTAINER and PROGRAMMATIC instances render "" here -- the contentSections include
     * owns those branches (title + children, and the type's Facelets fragment, respectively).
     */
    public String render(final ContentInstance instance) {
        if (instance == null || instance.getTemplateId() == null) {
            return "";
        }
        try {
            final ContentTemplate template = resolveTemplate(instance);
            if (template == null || template.getKind() != TemplateKind.STANDARD) {
                return "";
            }
            return ContentRenderer.render(template, carriedTo(instance, template), TripPageCommands::contentLink);
        } catch (final RuntimeException ex) {
            // A public page must render without this block rather than fail with it.
            log.error("Unable to render content: " + instance.getId(), ex);
            return "";
        }
    }

    /**
     * The values {@code template} renders: the instance's own, unless it stays on the latest version and was not
     * read through a section listing (which carries every such instance forward already), in which case a carried
     * COPY. The cached instance is never touched.
     */
    private ContentInstance carriedTo(final ContentInstance instance, final ContentTemplate template) {
        if (!instance.isStayOnLatest() || template.getVersion() == instance.getTemplateVersion()) {
            return instance;
        }
        final ContentInstance copy = instance.copy();
        followLatest(copy, Cached.YES);
        return copy;
    }

    /**
     * The markup a CONTAINER wraps BEFORE one of its children -- everything in its body up to
     * {@code {{child}}}. The container owns the row (list item, table row, heading) because it is the
     * container that iterates; the child template knows nothing about how it is being laid out.
     *
     * <p>Two calls rather than one because a child is a component tree, not a string: the page emits this,
     * then the child's real components, then {@link #childRowAfter}.
     */
    public String childRowBefore(final ContentInstance container, final ContentInstance child,
            final int index) {
        return ContentRenderer.fillContainerTokens(childRow(container, child, index).before(), container);
    }

    /** The markup a CONTAINER wraps AFTER one of its children; see {@link #childRowBefore}. */
    public String childRowAfter(final ContentInstance container, final ContentInstance child,
            final int index) {
        return ContentRenderer.fillContainerTokens(childRow(container, child, index).after(), container);
    }

    /**
     * The row comes from the version the container instance renders with, like every other template
     * resolution: the version it PINNED, or for a Stay on Latest container the template's current version (one
     * cached field, {@code DAO.getLatestTemplate}). Pinned stays the default because the unversioned lookup
     * rescans the whole template table whenever the cache is not authoritative, and the public render path
     * performs no live reads (RenderPathCacheTest); a pinned container takes a row change through the version
     * menu on its own edit dialog.
     */
    private ContentRenderer.ChildRow childRow(final ContentInstance container, final ContentInstance child,
            final int index) {
        try {
            return ContentRenderer.renderChildRow(containerBody(container).row(), child,
                    "PROGRAMMATIC".equals(getKind(child)), index);
        } catch (final RuntimeException ex) {
            // Same contract as render(): the page renders without the decoration rather than failing.
            log.error("Unable to render the container row for: " + (child == null ? null : child.getId()), ex);
            return ContentRenderer.renderChildRow(null, child, false, index);
        }
    }

    /**
     * The markup a CONTAINER emits ONCE before all of its children -- the wrapper ({@code <ul>},
     * {@code <table>}) that a per-child row cannot express. Empty unless the body delimits its row with
     * {@code {{children:start}}} / {@code {{children:end}}}.
     */
    public String childrenBefore(final ContentInstance container) {
        return ContentRenderer.fillContainerTokens(containerBody(container).beforeAll(), container);
    }

    /** The markup a CONTAINER emits ONCE after all of its children; see {@link #childrenBefore}. */
    public String childrenAfter(final ContentInstance container) {
        return ContentRenderer.fillContainerTokens(containerBody(container).afterAll(), container);
    }

    private ContentRenderer.ContainerBody containerBody(final ContentInstance container) {
        try {
            final ContentTemplate template = container == null ? null : resolveTemplate(container);
            return ContentRenderer.containerBody(template == null ? null : template.getBody());
        } catch (final RuntimeException ex) {
            log.error("Unable to read the container body of: " + container.getId(), ex);
            return ContentRenderer.containerBody(null);
        }
    }

    /**
     * The page-side gate for edit buttons: {@code #{content.canEdit(sectionKey, userId)}}, where the key is
     * a page key or a container instance's id. A site administrator or contentAdmin passes outright; a
     * container id also passes holders of ANY of that container's {@code editorPrivileges}. Pages must keep
     * this BEHIND the edit-mode check ({@code editMode and content.canEdit(...)}) -- the container
     * resolution is an uncached point read that anonymous renders must never reach.
     */
    public boolean canEdit(final String section, final Person.Id userId) {
        if (userId == null || section == null) {
            return false;
        }
        if (callerSource.get().isSiteAdmin()) {
            return true;
        }
        if (priv.check(PrivilegeCommands.CONTENT_ADMIN, null, userId)) {
            return true;
        }
        final ContentInstance container = containerOf(section);
        final Organization.Id org = orgOf(section, container);
        if (org != null && priv.check(PrivilegeCommands.CONTENT_ADMIN, org.getValue(), userId)) {
            return true;
        }
        final String trip = tripOf(section, container);
        if (trip != null && priv.check(PrivilegeCommands.TRIP_MGR, trip, userId)) {
            return true;
        }
        return editorPrivilegesOf(container).stream().anyMatch(name -> priv.check(name, null, userId));
    }

    /**
     * The trip whose page a section belongs to ({@code page:trip:{id}:...}, docs/conference.md), or null for
     * every other page. Like {@link #orgOf}, derived from the SECTION (a container's children through the
     * container row), never from the host.
     */
    static String tripOf(final String section, final ContentInstance container) {
        final String direct = OrgPageBootstrap.tripOf(section);
        if (direct != null) {
            return direct;
        }
        return container == null ? null : OrgPageBootstrap.tripOf(container.getSection());
    }

    /**
     * The organization whose SITE a section belongs to, or null for the shared and marketing pages (and
     * anything else). Derived from the SECTION, never from the request's host: a save carries its section,
     * and a forged section on an org host must not become editable, nor a legitimate edit be blocked by
     * which host the browser used. A container's children live under the container id, so the container
     * row (one point read, shared with the editor-privilege lookup) names the page.
     */
    static Organization.Id orgOf(final String section, final ContentInstance container) {
        final Organization.Id direct = orgOfKey(section);
        if (direct != null) {
            return direct;
        }
        return container == null ? null : orgOfKey(container.getSection());
    }

    /**
     * An org page key's org; the marketing pages' is the platform organization (when one exists); a trip page's
     * is the trip's owning organization, so that org's content admins edit it and its person picker offers that
     * org's members (a point-cached read).
     */
    private static Organization.Id orgOfKey(final String pageKey) {
        if (OrgPageBootstrap.isMarketingPageKey(pageKey)) {
            return OrgCommands.platformOrgId();
        }
        final String tripId = OrgPageBootstrap.tripOf(pageKey);
        if (tripId != null) {
            return DAO.getInstance().getTrip(tripId, Cached.YES)
                    .map(Trip::getOrgId)
                    .filter(id -> !id.isBlank())
                    .map(Organization.Id::from)
                    .orElse(null);
        }
        return OrgPageBootstrap.orgOf(pageKey);
    }

    /** The container instance a section key names (its children's section), or null for a page key. */
    private ContentInstance containerOf(final String section) {
        try {
            return DAO.getInstance().getContent(section, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the container for section: " + section, ex);
            return null;
        }
    }

    private static List<String> editorPrivilegesOf(final ContentInstance container) {
        return container == null || container.getEditorPrivileges() == null
                ? List.of() : container.getEditorPrivileges();
    }

    /**
     * Whether the caller may edit ANYTHING on a page -- the "Edit page" button's gate: contentAdmin (or
     * site admin) always may; otherwise any container section one of whose editorPrivileges the caller
     * holds.
     */
    public boolean canEditPage(final String pageKey, final Person.Id userId) {
        if (userId == null || pageKey == null) {
            return false;
        }
        if (canEdit(pageKey, userId)) {
            return true;
        }
        return readSection(pageKey).stream()
                .anyMatch(section -> holdsEditorPrivilege(section, userId));
    }

    /**
     * Whether a content page opens in EDIT MODE: the request asked for it ({@code ?edit=true}) AND the viewer
     * may edit the page ({@link #canEditPage}, or {@code alsoAllowed}: whatever else the host's own "Edit page"
     * button admits, a site admin's {@code showAll} and on the landing page a media admin). THE one capture
     * every content host uses ({@code viewScope.editMode = content.editModeFor(param.edit, ...)}): the hosts
     * used to pin {@code "true".equals(param.edit)} for any signed-in visitor and gate only the edit CONTROLS,
     * and edit mode lists every row ({@link #getForView}), so anyone signed in read a page's hidden and
     * not-yet-visible sections by adding the parameter. Signed-in paths only: this reads the privilege store.
     */
    public boolean editModeFor(final String requested, final String pageKey, final Person.Id userId,
            final boolean alsoAllowed) {
        return "true".equals(requested) && userId != null && (alsoAllowed || canEditPage(pageKey, userId));
    }

    private boolean holdsEditorPrivilege(final ContentInstance section, final Person.Id userId) {
        return section.getEditorPrivileges() != null && section.getEditorPrivileges().stream()
                .anyMatch(name -> priv.check(name, null, userId));
    }

    /**
     * The server-side gate every mutation re-checks: a global contentAdmin (or site admin, via Caller)
     * edits everything; an ORG-scoped contentAdmin edits sections of that org's own page and nothing else
     * -- {@link #orgOf} answers null for the shared and marketing pages, so an org grant can never reach
     * them, or another org's page; a container's editor-privilege holders edit that container's children.
     */
    private boolean mayEdit(final Caller caller, final String section) {
        if (caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            return true;
        }
        final ContentInstance container = containerOf(section);
        final Organization.Id org = orgOf(section, container);
        if (org != null && caller.has(PrivilegeCommands.CONTENT_ADMIN, org.getValue())) {
            return true;
        }
        final String trip = tripOf(section, container);
        if (trip != null && caller.has(PrivilegeCommands.TRIP_MGR, trip)) {
            return true;
        }
        return editorPrivilegesOf(container).stream().anyMatch(caller::has);
    }

    // ------------------------------------------------------------------ contentSections include helpers

    /**
     * The instances a viewer of a section should see: editors see everything (expired events included, so
     * they can fix them), the public sees only currently-visible ones. The host page captures this into
     * viewScope at initPage so decode and render iterate the same rows.
     */
    public List<ContentInstance> getForView(final String section, final boolean editMode) {
        return editMode ? getAllForSection(section) : getForSection(section);
    }

    /** Child lists per CONTAINER section id, for the same viewScope capture. Serializable (HashMap). */
    public HashMap<String, List<ContentInstance>> childrenFor(final List<ContentInstance> sections,
            final boolean editMode) {
        final HashMap<String, List<ContentInstance>> children = new HashMap<>();
        if (sections == null) {
            return children;
        }
        for (final ContentInstance section : sections) {
            if ("CONTAINER".equals(getKind(section))) {
                children.put(section.getId(), getForView(section.getId(), editMode));
            }
        }
        return children;
    }

    /**
     * The ids of {@code instances} — what a view may keep. viewScope is serialized into the HTTP session,
     * so the host page freezes IDS at first render (row identity for decode: postback buttons must iterate
     * the same rows the user clicked) and re-resolves the instances per request via
     * {@link #forFrozenIds}.
     */
    public List<String> idsOf(final List<ContentInstance> instances) {
        final List<String> ids = new ArrayList<>();
        if (instances != null) {
            for (final ContentInstance instance : instances) {
                ids.add(instance.getId());
            }
        }
        return ids;
    }

    /** Child-id lists per CONTAINER section id — the view-frozen shape of {@link #childrenFor}. */
    public HashMap<String, List<String>> childIdsFor(final List<ContentInstance> sections,
            final boolean editMode) {
        final HashMap<String, List<String>> childIds = new HashMap<>();
        final HashMap<String, List<ContentInstance>> children = childrenFor(sections, editMode);
        for (final Map.Entry<String, List<ContentInstance>> entry : children.entrySet()) {
            childIds.put(entry.getKey(), idsOf(entry.getValue()));
        }
        return childIds;
    }

    /**
     * The current instances of {@code section} in the FROZEN id order: rows deleted since the freeze drop
     * out, rows added since it stay invisible until the reload that every structural edit forces — so a
     * postback decodes exactly the rows its render produced, while the objects themselves are always the
     * cache's current copies rather than a session snapshot.
     */
    public List<ContentInstance> forFrozenIds(final String section, final boolean editMode,
            final List<String> ids) {
        final List<ContentInstance> resolved = new ArrayList<>();
        if (ids == null) {
            return resolved;
        }
        final HashMap<String, ContentInstance> current = new HashMap<>();
        for (final ContentInstance instance : getForView(section, editMode)) {
            current.put(instance.getId(), instance);
        }
        for (final String id : ids) {
            final ContentInstance instance = current.get(id);
            if (instance != null) {
                resolved.add(instance);
            }
        }
        return resolved;
    }

    /** Resolves a frozen child-id map back to instances, per {@link #forFrozenIds}. */
    public HashMap<String, List<ContentInstance>> childrenForFrozen(
            final Map<String, List<String>> childIds, final boolean editMode) {
        final HashMap<String, List<ContentInstance>> children = new HashMap<>();
        if (childIds != null) {
            for (final Map.Entry<String, List<String>> entry : childIds.entrySet()) {
                children.put(entry.getKey(), forFrozenIds(entry.getKey(), editMode, entry.getValue()));
            }
        }
        return children;
    }

    /** The instance's template kind as a string for EL branching: STANDARD, CONTAINER or PROGRAMMATIC. */
    public String getKind(final ContentInstance instance) {
        if (instance == null) {
            return "STANDARD";
        }
        final ContentTemplate template = resolveTemplate(instance);
        return template == null ? "STANDARD" : template.getKind().name();
    }

    /** The programmatic type id behind an instance, or "" -- drives the fragment rendered= guards. */
    public String typeId(final ContentInstance instance) {
        if (instance == null) {
            return "";
        }
        final ContentTemplate template = resolveTemplate(instance);
        return template == null || template.getProgrammaticTypeId() == null
                ? "" : template.getProgrammaticTypeId();
    }

    /**
     * The prompts the content dialog builds its form from: the instance's PINNED template version, except
     * that a PROGRAMMATIC template answers its registered type's live property list
     * ({@link ProgrammaticTypes#placeholdersOf}) -- the dialog used to iterate the stored row's
     * placeholders, so a property added to a type after the starter was installed (the shared-site
     * "Organizations shown" list) never appeared on production's pilgrimage and photo-album sections.
     * Empty for an unresolvable template.
     */
    public List<Placeholder> placeholdersOf(final ContentInstance instance) {
        if (instance == null) {
            return List.of();
        }
        return ProgrammaticTypes.placeholdersOf(resolveTemplate(instance));
    }

    /**
     * A container's on-page title HTML (default heading for plain text, verbatim for markup) -- unless the
     * container's body places the title itself with {@code {{container:title}}} (the band templates do),
     * in which case this answers "" so the heading is not written twice. The body comes from the same
     * pinned, cached template lookup the row uses, so a public page view costs no extra read.
     */
    public String renderTitle(final ContentInstance instance) {
        if (instance == null) {
            return "";
        }
        return placesOwnTitle(instance) ? "" : ContentRenderer.renderContainerTitle(instance.getTitle());
    }

    private boolean placesOwnTitle(final ContentInstance container) {
        try {
            final ContentTemplate template = resolveTemplate(container);
            return template != null && ContentRenderer.hasContainerTitleSlot(template.getBody());
        } catch (final RuntimeException ex) {
            // Same contract as render(): the page shows the default heading rather than failing.
            log.error("Unable to read the container body of: " + container.getId(), ex);
            return false;
        }
    }

    /** Whether the instance is publicly visible right now (edit mode renders hidden ones dimmed). */
    public boolean isVisibleNow(final ContentInstance instance) {
        return instance != null && instance.isVisibleAt(LocalDateTime.now());
    }

    /**
     * The templates the Add dialog may offer for a section: inside a container, its effective allow-list
     * -- the container INSTANCE's when set, else its template's, else any non-container template; at page
     * level, everything but the templates marked "only inside a group" ({@code childOnly}) -- always narrowed
     * to what the request's SITE may use: an org site sees the shared
     * templates plus its own, any other site only the shared ones (an org's template never reaches another
     * tenant's page, or the shared page).
     */
    public List<ContentTemplate> getTemplateChoicesFor(final String section) {
        final List<ContentTemplate> all;
        try {
            // MAIL templates are email bodies: they have no instances and never render on a page, so no
            // Add dialog anywhere may offer one.
            final SiteContext site = SiteContext.current();
            all = DAO.getInstance().getAllTemplates(Cached.NO).stream()
                    .filter(t -> t.getKind() != TemplateKind.MAIL)
                    .filter(t -> !t.isOrgOwned() || site.isSiteOf(t.getOrgId()))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list templates for section: " + section, ex);
            return List.of();
        }
        final ContentInstance container;
        try {
            container = section == null ? null : DAO.getInstance().getContent(section, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve container for template choices: " + section, ex);
            return List.of();
        }
        if (container == null) {
            // A page key: any kind may become a section, except a leaf made for one group's row (childOnly:
            // a feature card on its own has no heading and no grid), and except that a trip's page leaves out
            // the types that list OTHER trips (fitsTripPage, docs/conference.md), and any other page the types
            // made for a trip's own page alone (onlyTripPage: its pictures and facts).
            final boolean tripPage = OrgPageBootstrap.tripOf(section) != null;
            return all.stream()
                    .filter(t -> !t.isChildOnly())
                    .filter(t -> tripPage ? fitsTripPage(t) : !onlyTripPage(t))
                    .filter(this::offeredToCaller)
                    .toList();
        }
        final boolean groupOnTripPage = OrgPageBootstrap.tripOf(container.getSection()) != null;
        final ContentTemplate containerTemplate =
                DAO.getInstance().getTemplate(container.getTemplateId(), Cached.NO).orElse(null);
        final List<String> allowed = effectiveAllowList(container, containerTemplate);
        return all.stream()
                .filter(t -> t.getKind() != TemplateKind.CONTAINER)
                .filter(t -> allowed == null || allowed.isEmpty() || allowed.contains(t.getId()))
                .filter(t -> groupOnTripPage || !onlyTripPage(t))
                .filter(this::offeredToCaller)
                .toList();
    }

    /**
     * Whether the Add dialog may offer {@code template} to this editor: every template, but a programmatic type
     * that answers no for them ({@code ProgrammaticContentTemplate.offeredTo}: the metrics graph, site admins only).
     */
    private boolean offeredToCaller(final ContentTemplate template) {
        if (template.getKind() != TemplateKind.PROGRAMMATIC) {
            return true;
        }
        final Caller caller = callerSource.get();
        return ProgrammaticTypes.byId(template.getProgrammaticTypeId()).map(type -> type.offeredTo(caller))
                .orElse(true);
    }

    /** Whether a template is a type made for a trip's own page alone (its pictures, its facts). */
    private static boolean onlyTripPage(final ContentTemplate template) {
        return template.getKind() == TemplateKind.PROGRAMMATIC
                && ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                        .map(ProgrammaticContentTemplate::onlyTripPage).orElse(false);
    }

    private static boolean fitsTripPage(final ContentTemplate template) {
        return template.getKind() != TemplateKind.PROGRAMMATIC
                || ProgrammaticTypes.byId(template.getProgrammaticTypeId())
                        .map(ProgrammaticContentTemplate::fitsTripPage).orElse(true);
    }

    /**
     * The Add flow's picker skip: when a section offers exactly ONE template there is no choice to make,
     * so this answers a started instance and the dialog opens straight on its form; with several (or no)
     * choices it answers null and the dialog shows the picker.
     */
    public ContentInstance autoStartContent(final String section) {
        final List<ContentTemplate> choices = getTemplateChoicesFor(section);
        return choices.size() == 1 ? createContent(section, choices.getFirst().getId()) : null;
    }

    // ---------------------------------------------------------------- template-version migration (dialog)

    /** The version menu's "Stay on Latest" choice, as the menu submits it (every other choice is a number). */
    public static final String STAY_ON_LATEST = "latest";

    /**
     * Whether the dialog offers the "Template version" menu: for every instance whose template exists, new ones
     * and single-version templates included, since "Stay on Latest" is a choice even where no other version is.
     */
    public boolean offersVersionMenu(final ContentInstance instance) {
        return instance != null && instance.getTemplateId() != null
                && !retainedTemplates(instance.getTemplateId()).isEmpty();
    }

    /**
     * The version menu's values: {@link #STAY_ON_LATEST} first, then every selectable version, newest first. Stay on
     * Latest is left out for an item that may not follow the template's current version ({@link #mayFollow}), since
     * choosing it would only be refused; an item already marked keeps it, so the menu can show what is stored.
     */
    public List<String> versionChoices(final ContentInstance instance) {
        if (!offersVersionMenu(instance)) {
            return List.of();
        }
        final List<String> choices = new ArrayList<>();
        if (instance.isStayOnLatest() || mayFollowCurrent(instance)) {
            choices.add(STAY_ON_LATEST);
        }
        getTemplateVersions(instance).forEach(version -> choices.add(String.valueOf(version)));
        return choices;
    }

    /** One version-menu row's label: "Stay on Latest", or {@link #versionLabel} for a version number. */
    public String choiceLabel(final ContentInstance instance, final String choice) {
        if (STAY_ON_LATEST.equals(choice)) {
            return Text.get("feedback.contentCommands.stayOnLatest");
        }
        final Integer version = parseVersion(choice);
        return version == null ? "" : versionLabel(instance, version);
    }

    /**
     * The template versions this instance may be pinned to, newest first: every version still retained on
     * the template row that {@link #mayFollow} lets it use (never one owned by an organization this item was not
     * authored for), plus its own (which may already have aged out of retention -- it must stay selectable, or
     * reopening the dialog would silently look like an upgrade).
     */
    public List<Integer> getTemplateVersions(final ContentInstance instance) {
        if (instance == null || instance.getTemplateId() == null) {
            return List.of();
        }
        final List<ContentTemplate> retained = retainedTemplates(instance.getTemplateId());
        final ContentTemplate saved = savedIn(retained, instance);
        final List<Integer> versions = new ArrayList<>();
        // Only versions this item may move to (mayFollow): a version owned by another organization is not offered.
        retained.stream().filter(t -> mayFollow(saved, t)).forEach(t -> versions.add(t.getVersion()));
        if (instance.getTemplateVersion() > 0 && !versions.contains(instance.getTemplateVersion())) {
            versions.add(instance.getTemplateVersion());
        }
        return versions.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    }

    /** The template's current version from its retained history (the dialog's request memo), or null. */
    private ContentTemplate currentRetained(final String templateId) {
        if (templateId == null) {
            return null;
        }
        return retainedTemplates(templateId).stream().max(Comparator.comparingInt(ContentTemplate::getVersion))
                .orElse(null);
    }

    /** Whether the item may follow the template's current version: the Stay on Latest choice's own guard. */
    private boolean mayFollowCurrent(final ContentInstance instance) {
        final ContentTemplate current = currentRetained(instance.getTemplateId());
        return current != null && mayFollow(savedIn(retainedTemplates(instance.getTemplateId()), instance), current);
    }

    /** The instance's saved-against version among {@code retained}, or null when it is not retained. */
    private static ContentTemplate savedIn(final List<ContentTemplate> retained, final ContentInstance instance) {
        return retained.stream().filter(t -> t.getVersion() == instance.getTemplateVersion()).findFirst()
                .orElse(null);
    }

    /**
     * Every retained version of a template, read ONCE per request: the version menu asks for it from several EL
     * calls per row (show it, its values, each label, the hint), in several JSF phases, on every dialog now.
     */
    private List<ContentTemplate> retainedTemplates(final String templateId) {
        return RequestMemo.get("retainedTemplates:" + templateId, () -> readRetainedTemplates(templateId));
    }

    private static List<ContentTemplate> readRetainedTemplates(final String templateId) {
        try {
            return DAO.getInstance().getTemplateRecord(templateId, Cached.NO)
                    .map(TemplateRecord::getAllVersions).orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to load template history: " + templateId, ex);
            return List.of();
        }
    }

    /**
     * The version menu's selected value (menus exchange strings): {@link #STAY_ON_LATEST} for an instance that
     * follows the latest version, else its pinned version.
     */
    public String pinnedVersion(final ContentInstance instance) {
        if (instance == null) {
            return "";
        }
        return instance.isStayOnLatest() ? STAY_ON_LATEST : String.valueOf(instance.getTemplateVersion());
    }

    /** The version menu's row label, marking the newest one and the instance's own. */
    public String versionLabel(final ContentInstance instance, final int version) {
        final ContentTemplate current = instance == null ? null : currentRetained(instance.getTemplateId());
        if (current != null && current.getVersion() == version) {
            return Text.get("feedback.contentCommands.versionLatest", String.valueOf(version));
        }
        if (instance != null && !instance.isStayOnLatest() && instance.getTemplateVersion() == version) {
            return Text.get("feedback.contentCommands.versionInUse", String.valueOf(version));
        }
        return "v" + version;
    }

    /**
     * Whether a newer version of this instance's template exists -- the dialog's upgrade hint. Never for an
     * instance that stays on the latest version: it moves on by itself.
     */
    public boolean isTemplateOutdated(final ContentInstance instance) {
        if (instance == null || instance.isStayOnLatest()) {
            return false;
        }
        final List<Integer> versions = getTemplateVersions(instance);
        return !versions.isEmpty() && versions.getFirst() > instance.getTemplateVersion();
    }

    /**
     * Re-pins the dialog's working copy to another version of its own template, carrying the filled-in
     * values across by {@link TemplateValueMigrator}'s rules. Nothing is stored until Apply, so a migration
     * that lands badly is abandoned by cancelling. What moved and what was lost is reported to the editor --
     * a dropped value must never be a surprise.
     *
     * <p>{@link #STAY_ON_LATEST} retargets to the NEWEST version the same way and marks the copy to follow the
     * template from then on; a version number pins it (clearing that mark), even the version it is already on.
     *
     * @param version the wanted version, as the menu submits it.
     */
    public boolean retargetTemplateVersion(final ContentInstance instance, final String version) {
        if (instance == null) {
            return false;
        }
        if (STAY_ON_LATEST.equals(version)) {
            return retargetToLatest(instance);
        }
        final Integer wanted = parseVersion(version);
        if (wanted == null) {
            return false;
        }
        if (wanted == instance.getTemplateVersion()) {
            return pinInPlace(instance);
        }
        final ContentTemplate to = loadTemplateVersion(instance.getTemplateId(), wanted, Cached.NO);
        if (to == null) {
            PageFeedback.error(Text.get("feedback.contentCommands.versionUnavailable"),
                    Text.get("feedback.contentCommands.versionNotRetained", String.valueOf(wanted)));
            return false;
        }
        if (!mayFollow(savedAgainst(instance, Cached.NO), to)) {
            PageFeedback.error(Text.get("feedback.contentCommands.versionUnavailable"),
                    Text.get("feedback.contentCommands.versionOtherOrg", String.valueOf(wanted)));
            return false;
        }
        moveValues(instance, to);
        instance.setStayOnLatest(false);
        return true;
    }

    /** "Stay on Latest": the copy moves to the template's newest version and is marked to follow it. */
    private boolean retargetToLatest(final ContentInstance instance) {
        final ContentTemplate to = currentTemplate(instance.getTemplateId(), Cached.NO);
        if (to == null) {
            PageFeedback.error(Text.get("feedback.contentCommands.versionUnavailable"),
                    Text.get("feedback.contentCommands.templateGone"));
            return false;
        }
        if (!mayFollow(savedAgainst(instance, Cached.NO), to)) {
            PageFeedback.error(Text.get("feedback.contentCommands.versionUnavailable"),
                    Text.get("feedback.contentCommands.newestOtherOrg"));
            return false;
        }
        moveValues(instance, to);
        instance.setStayOnLatest(true);
        return true;
    }

    /** A version number equal to the copy's own: nothing moves, but a copy that followed the latest is pinned. */
    private static boolean pinInPlace(final ContentInstance instance) {
        if (!instance.isStayOnLatest()) {
            return false;
        }
        instance.setStayOnLatest(false);
        PageFeedback.info(Text.get("feedback.contentCommands.nowEditing",
                String.valueOf(instance.getTemplateVersion()), unchanged()));
        return true;
    }

    /**
     * Re-keys the copy's values from the version they were saved against onto {@code to}, and says what moved.
     * The source is the saved-against version, not what the copy renders with: a copy that stays on the latest
     * renders the newest version, but its values were typed against {@code templateVersion}.
     */
    private void moveValues(final ContentInstance instance, final ContentTemplate to) {
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                ProgrammaticTypes.placeholdersOf(resolvePinned(instance)), ProgrammaticTypes.placeholdersOf(to),
                instance.getValues());
        instance.setTemplateVersion(to.getVersion());
        instance.setValues(result.values());
        // The whole report goes in the SUMMARY: this site's growl renders details only for the
        // URL-parameter messages (template.xhtml's hasDetail), so a detail-only warning is invisible.
        PageFeedback.info(Text.get("feedback.contentCommands.nowEditing", String.valueOf(to.getVersion()),
                migrationSummary(result, true)));
    }

    /** The migration summary when nothing was renamed or dropped. */
    private static String unchanged() {
        return Text.get("feedback.contentCommands.unchanged");
    }

    /** What a migration did, for a growl; {@code cancelUndoes} adds that the dialog's Cancel keeps the old values. */
    private static String migrationSummary(final TemplateValueMigrator.Result result, final boolean cancelUndoes) {
        final StringBuilder summary = new StringBuilder();
        result.renamed().forEach((oldName, newName) -> summary
                .append(Text.get("feedback.contentCommands.valueMoved", oldName, newName)).append("; "));
        if (!result.dropped().isEmpty()) {
            final String dropped = String.join(", ", result.dropped());
            summary.append(cancelUndoes ? Text.get("feedback.contentCommands.droppedCancelKeeps", dropped)
                    : Text.get("feedback.contentCommands.dropped", dropped));
        }
        return summary.isEmpty() ? unchanged() : summary.toString().replaceAll("; $", "");
    }

    private static ContentTemplate loadTemplateVersion(final String templateId, final int version,
            final Cached cached) {
        try {
            return DAO.getInstance().getTemplate(templateId, version, cached).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to load template " + templateId + " v" + version, ex);
            return null;
        }
    }

    private static Integer parseVersion(final String version) {
        try {
            return version == null || version.isBlank() ? null : Integer.valueOf(version.trim());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    /** The section's instance ids in display order, as the MUTABLE list the arrange dialog drags. */
    public ArrayList<String> idsFor(final String section, final boolean editMode) {
        final ArrayList<String> ids = new ArrayList<>();
        getForView(section, editMode).forEach(instance -> ids.add(instance.getId()));
        return ids;
    }

    /** An instance's title by id, for the arrange dialog's row labels (admin path; point read is fine). */
    public String titleOf(final String id) {
        // The stored row, not getContent: the arrange dialog's labels must not carry a Stay on Latest row (and
        // report the carry) once per row.
        final ContentInstance row = DAO.getInstance().getContent(id, Cached.NO).orElse(null);
        if (row != null && row.getTitle() != null && !row.getTitle().isBlank()) {
            return row.getTitle();
        }
        final String label = untitledLabel(row);
        return label.isEmpty() ? id : label;
    }

    /** Autocomplete for the Editors chips: global privilege names matching the query (all on empty). */
    public List<String> completeEditorPriv(final String query) {
        final String match = query == null ? "" : query.trim().toLowerCase();
        return priv.getGlobalPrivileges().stream()
                .map(Privilege::getName)
                .filter(name -> name.toLowerCase().contains(match))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** The valid privilege names as a JS array literal, for the dialog's invalid-chip highlighting. */
    public String getEditorPrivNamesJson() {
        return priv.getGlobalPrivileges().stream()
                .map(Privilege::getName)
                .map(ContentCommands::jsString)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The edit-mode frame classes for section N: a rotating border palette so adjacent sections (each
     * framed WITH its buttons) read as distinct groups.
     */
    public String frameClass(final int index) {
        return "tripEditFrame tripEditFrame" + Math.floorMod(index, 5);
    }

    /**
     * Whether the content dialog shows a programmatic property on THIS site: a shared-site-only property
     * (provider and org curation -- see {@code ProgrammaticContentTemplate.sharedSiteOnlyProperties}) is
     * hidden on an organization's own site, where the site is implicitly the only provider.
     */
    public boolean showsProperty(final ContentInstance instance, final String propertyName) {
        if (!SiteContext.current().isOrg()) {
            return true;
        }
        final String typeId = typeId(instance);
        return typeId.isEmpty() || ProgrammaticTypes.byId(typeId)
                .map(type -> !type.sharedSiteOnlyProperties().contains(propertyName))
                .orElse(true);
    }

    /**
     * Dropdown options for a CHOICE property: a programmatic type's provider, or a hand-written template's own
     * options (2026-11-25). A value already held that is not one of them (a tone typed by hand before the prompt
     * became a menu) is offered too, last and marked, so the menu shows it and posts it back unchanged: a
     * PrimeFaces menu whose items omit its value silently posts its first option instead.
     */
    public List<ProgrammaticContentTemplate.Choice> getChoices(final ContentInstance instance,
            final String propertyName) {
        final String typeId = typeId(instance);
        if (!typeId.isEmpty()) {
            return ProgrammaticTypes.byId(typeId)
                    .map(type -> type.choicesFor(propertyName))
                    .orElse(List.of());
        }
        final Placeholder ph = instance == null ? null : placeholderNamed(resolveTemplate(instance), propertyName);
        if (ph == null || !ph.isFixedChoice()) {
            return List.of();
        }
        final List<ProgrammaticContentTemplate.Choice> choices = new ArrayList<>();
        ph.getOptions().forEach(option -> choices.add(new ProgrammaticContentTemplate.Choice(option, option)));
        final String held = instance.getValues().get(propertyName);
        if (held != null && !held.isBlank() && !ph.getOptions().contains(held)) {
            choices.add(new ProgrammaticContentTemplate.Choice(held,
                    Text.get("feedback.contentCommands.notAChoice", held)));
        }
        return choices;
    }

    /**
     * The values held by this item that its menus do not list, as "Tone: Dark", for the drawer's note when it
     * opens: such a value still saves as it is, and choosing another one replaces it.
     */
    public List<String> unlistedChoices(final ContentInstance instance) {
        final ContentTemplate template = instance == null ? null : resolveTemplate(instance);
        if (template == null || template.getKind() == TemplateKind.PROGRAMMATIC) {
            return List.of();
        }
        final List<String> unlisted = new ArrayList<>();
        for (final Placeholder ph : template.getPlaceholders()) {
            final String held = instance.getValues().get(ph.getName());
            if (ph.isFixedChoice() && held != null && !held.isBlank() && !ph.getOptions().contains(held)) {
                unlisted.add(promptLabel(ph) + ": " + held);
            }
        }
        return unlisted;
    }

    private static Placeholder placeholderNamed(final ContentTemplate template, final String name) {
        if (template == null || name == null) {
            return null;
        }
        return template.getPlaceholders().stream()
                .filter(ph -> name.equals(ph.getName()))
                .findFirst()
                .orElse(null);
    }

    private List<ContentInstance> readSection(final String section) {
        if (section == null) {
            // No page here (tripDetails for a pilgrimage, or a conference this site does not reach).
            return List.of();
        }
        try {
            final List<ContentInstance> rows = DAO.getInstance().getContentForSection(section, Cached.YES);
            // Each read deserializes fresh objects, so carrying a Stay on Latest row forward here changes no
            // cached or stored copy; every page helper then sees the values the current version renders.
            rows.forEach(row -> followLatest(row, Cached.YES));
            return rows;
        } catch (final RuntimeException ex) {
            // A page must still render if the content table is unavailable; the section is just empty.
            log.error("Unable to list content for section: " + section, ex);
            return List.of();
        }
    }

    private int retainCount() {
        return retainCount(config);
    }

    /** How many previous versions a content or template row keeps: THE one read of the setting and its ceiling. */
    static int retainCount(final ConfigCommands config) {
        return config.getInt(KnownSettings.CONTENT_VERSIONS_RETAINED, 0, MAX_RETAINED_VERSIONS);
    }

    private static void audit(final Caller caller, final String id, final String message) {
        Audit.builder(AuditAction.CONTENT, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_CONTENT, id)
                .message(message)
                .log();
    }

    private static ContentInstance blank() {
        return new ContentInstance(UUID.randomUUID().toString(), null, "", null, 0, new HashMap<>(),
                null, 0, 0, null, null);
    }
}
