package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.HtmlFragmentValidator;
import org.paulsens.trip.content.ProgrammaticContentTemplate;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.TemplateKind;

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

    private final PrivilegeCommands priv = new PrivilegeCommands();
    private final ConfigCommands config = new ConfigCommands();
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
            return DAO.getInstance().getContent(id).map(ContentInstance::copy).orElseGet(ContentCommands::blank);
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
        final ContentTemplate template = DAO.getInstance().getTemplate(templateId).orElse(null);
        final ContentInstance created = blank();
        created.setSection(section);
        if (template != null) {
            created.setTemplateId(template.getId());
            created.setTemplateVersion(template.getVersion());
            template.getPlaceholders().forEach(ph -> created.getValues().putIfAbsent(ph.getName(), ""));
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
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved",
                    "You do not have permission to edit this section.");
            return false;
        }
        guardContainerConfig(caller, instance);
        final String problem = validateForSave(instance);
        if (problem != null) {
            log.warn("Refusing content save of '{}': {}", instance.getId(), problem);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", problem);
            return false;
        }
        instance.setModifiedBy(caller.auditActor().email());
        final boolean saved;
        try {
            saved = DAO.getInstance().saveContent(instance, retainCount());
        } catch (final RuntimeException ex) {
            log.error("Unable to save content: " + instance.getId(), ex);
            return false;
        }
        if (!saved) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved",
                    "The content could not be saved. It may have been edited by someone else; reopen it and try again.");
        }
        if (saved) {
            audit(caller, instance.getId(),
                    "Saved v" + instance.getVersion() + " of '" + instance.getTitle() + "' in "
                            + instance.getSection());
        }
        return saved;
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
        final ContentInstance stored = DAO.getInstance().getContent(instance.getId()).orElse(null);
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
     * Structural/containment validation. @return a user-facing problem, or null when the save may proceed.
     */
    private String validateForSave(final ContentInstance instance) {
        if (instance.getId() != null && instance.getId().equals(instance.getSection())) {
            return "An item cannot be placed inside itself.";
        }
        final String htmlProblem = validateAuthoredHtml(instance);
        if (htmlProblem != null) {
            return htmlProblem;
        }
        final ContentInstance parent;
        try {
            parent = DAO.getInstance().getContent(instance.getSection()).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the parent of content: " + instance.getId(), ex);
            return "The containing section could not be checked; try again.";
        }
        return parent == null ? null : validateAsChild(instance, parent);
    }

    /** The title (when it carries markup) and every RICH_TEXT value must be structurally sound HTML. */
    private String validateAuthoredHtml(final ContentInstance instance) {
        if (instance.getTitle() != null && instance.getTitle().indexOf('<') >= 0) {
            final String titleProblem = HtmlFragmentValidator.validate(instance.getTitle());
            if (titleProblem != null) {
                return "Title: " + titleProblem;
            }
        }
        final ContentTemplate template = resolveTemplate(instance);
        if (template == null) {
            return null;
        }
        for (final Placeholder ph : template.getPlaceholders()) {
            if (ph.getType() != Placeholder.Type.RICH_TEXT) {
                continue;
            }
            final String problem = HtmlFragmentValidator.validate(instance.getValues().get(ph.getName()));
            if (problem != null) {
                return ph.getLabel() + ": " + problem;
            }
        }
        return null;
    }

    /** The container rules: kind, allow-list, and head count, read from the container's CURRENT template. */
    private String validateAsChild(final ContentInstance child, final ContentInstance parent) {
        final ContentTemplate childTemplate = resolveTemplate(child);
        if (childTemplate != null && childTemplate.getKind() == TemplateKind.CONTAINER) {
            return "A container cannot be placed inside another container.";
        }
        final ContentTemplate containerTemplate =
                DAO.getInstance().getTemplate(parent.getTemplateId()).orElse(null);
        if (containerTemplate == null || containerTemplate.getKind() != TemplateKind.CONTAINER) {
            return null;    // parent id collides with a non-container instance; nothing to enforce
        }
        final List<String> allowed = effectiveAllowList(parent, containerTemplate);
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(child.getTemplateId())) {
            return "This container does not allow '" + child.getTemplateId() + "' content.";
        }
        final Integer max = containerTemplate.getMaxChildren();
        if (max != null) {
            final long others = readSection(parent.getId()).stream()
                    .filter(sibling -> !sibling.getId().equals(child.getId()))
                    .count();
            if (others >= max) {
                return "This container is full (limit " + max + ").";
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

    private ContentTemplate resolveTemplate(final ContentInstance instance) {
        if (instance.getTemplateId() == null) {
            return null;
        }
        try {
            return DAO.getInstance().getTemplate(instance.getTemplateId(), instance.getTemplateVersion())
                    .or(() -> DAO.getInstance().getTemplate(instance.getTemplateId()))
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the template of content: " + instance.getId(), ex);
            return null;
        }
    }

    /**
     * Deletes an instance (all versions); refused without edit rights on its section. Deleting a CONTAINER
     * instance cascades to its children first -- each through the DAO's surgical single-row delete, never a
     * cache invalidation.
     */
    public boolean deleteContent(final String id) {
        final ContentInstance existing;
        try {
            existing = DAO.getInstance().getContent(id).orElse(null);
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
        final ContentTemplate template = resolveTemplate(existing);
        if (template != null && template.getKind() == TemplateKind.CONTAINER) {
            for (final ContentInstance child : readSection(id)) {
                if (DAO.getInstance().deleteContent(child.getId())) {
                    audit(caller, child.getId(), "Deleted '" + child.getTitle()
                            + "' with its container '" + existing.getTitle() + "'");
                }
            }
        }
        final boolean deleted = DAO.getInstance().deleteContent(id);
        if (deleted) {
            audit(caller, id, "Deleted '" + existing.getTitle() + "' from " + existing.getSection());
        }
        return deleted;
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

    /** Every retained version of an instance, current first, for the history dialog. */
    public List<ContentInstance> getHistory(final String id) {
        try {
            return DAO.getInstance().getContentRecord(id).map(ContentRecord::getAllVersions).orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to load content history: " + id, ex);
            return List.of();
        }
    }

    /** Restores a retained version as the new current (itself a new version -- history stays linear). */
    public boolean restoreContent(final String id, final int version) {
        final ContentRecord record;
        try {
            record = DAO.getInstance().getContentRecord(id).orElse(null);
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
        restored.setModifiedBy(caller.auditActor().email());
        final boolean saved;
        try {
            saved = DAO.getInstance().saveContent(restored, retainCount());
        } catch (final RuntimeException ex) {
            log.error("Unable to restore content: " + id, ex);
            return false;
        }
        if (saved) {
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
            return ContentRenderer.render(template, instance);
        } catch (final RuntimeException ex) {
            // A public page must render without this block rather than fail with it.
            log.error("Unable to render content: " + instance.getId(), ex);
            return "";
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
        return containerEditorPrivileges(section).stream().anyMatch(name -> priv.check(name, null, userId));
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

    private boolean holdsEditorPrivilege(final ContentInstance section, final Person.Id userId) {
        return section.getEditorPrivileges() != null && section.getEditorPrivileges().stream()
                .anyMatch(name -> priv.check(name, null, userId));
    }

    private boolean mayEdit(final Caller caller, final String section) {
        if (caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            return true;
        }
        return containerEditorPrivileges(section).stream().anyMatch(caller::has);
    }

    /** The editor privileges of the container instance a section key names; empty for page keys. */
    private List<String> containerEditorPrivileges(final String section) {
        try {
            return DAO.getInstance().getContent(section)
                    .map(ContentInstance::getEditorPrivileges)
                    .orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the container for section: " + section, ex);
            return List.of();
        }
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

    /** A container's on-page title HTML (default heading for plain text, verbatim for markup). */
    public String renderTitle(final ContentInstance instance) {
        return instance == null ? "" : ContentRenderer.renderContainerTitle(instance.getTitle());
    }

    /** Whether the instance is publicly visible right now (edit mode renders hidden ones dimmed). */
    public boolean isVisibleNow(final ContentInstance instance) {
        return instance != null && instance.isVisibleAt(LocalDateTime.now());
    }

    /**
     * The templates the Add dialog may offer for a section: inside a container, its effective allow-list
     * -- the container INSTANCE's when set, else its template's, else any non-container template; at page
     * level, everything.
     */
    public List<ContentTemplate> getTemplateChoicesFor(final String section) {
        final List<ContentTemplate> all;
        try {
            all = DAO.getInstance().getAllTemplates();
        } catch (final RuntimeException ex) {
            log.error("Unable to list templates for section: " + section, ex);
            return List.of();
        }
        final ContentInstance container;
        try {
            container = section == null ? null : DAO.getInstance().getContent(section).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve container for template choices: " + section, ex);
            return List.of();
        }
        if (container == null) {
            return all;     // a page key: any kind may become a section
        }
        final ContentTemplate containerTemplate =
                DAO.getInstance().getTemplate(container.getTemplateId()).orElse(null);
        final List<String> allowed = effectiveAllowList(container, containerTemplate);
        return all.stream()
                .filter(t -> t.getKind() != TemplateKind.CONTAINER)
                .filter(t -> allowed == null || allowed.isEmpty() || allowed.contains(t.getId()))
                .toList();
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

    /** The section's instance ids in display order, as the MUTABLE list the arrange dialog drags. */
    public java.util.ArrayList<String> idsFor(final String section, final boolean editMode) {
        final java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        getForView(section, editMode).forEach(instance -> ids.add(instance.getId()));
        return ids;
    }

    /** An instance's title by id, for the arrange dialog's row labels (admin path; point read is fine). */
    public String titleOf(final String id) {
        final String title = getContent(id).getTitle();
        return title == null || title.isBlank() ? id : title;
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

    /** Dropdown options for a CHOICE property of a programmatic instance; empty otherwise. */
    public List<ProgrammaticContentTemplate.Choice> getChoices(final ContentInstance instance,
            final String propertyName) {
        final String typeId = typeId(instance);
        if (typeId.isEmpty()) {
            return List.of();
        }
        return ProgrammaticTypes.byId(typeId)
                .map(type -> type.choicesFor(propertyName))
                .orElse(List.of());
    }

    private List<ContentInstance> readSection(final String section) {
        try {
            return DAO.getInstance().getContentForSection(section);
        } catch (final RuntimeException ex) {
            // A page must still render if the content table is unavailable; the section is just empty.
            log.error("Unable to list content for section: " + section, ex);
            return List.of();
        }
    }

    private int retainCount() {
        return config.getInt(KnownSettings.CONTENT_VERSIONS_RETAINED, 0, 50);
    }

    private void audit(final Caller caller, final String id, final String message) {
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
