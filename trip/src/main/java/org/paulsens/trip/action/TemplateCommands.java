package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.HtmlFragmentValidator;
import org.paulsens.trip.content.ProgrammaticContentTemplate;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.RichTextRules;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.TemplateRecord;
import org.paulsens.trip.cache.Cached;

/**
 * Content-template management, exposed to the template-manager page as {@code #{contentTemplate}}.
 *
 * <p>Everything here requires {@code contentAdmin}: template bodies are raw HTML rendered unescaped on
 * public pages, so authoring one is script access. The narrower section privileges ({@code eventAdmin})
 * only ever fill placeholder values through {@code ContentCommands}.
 */
@Slf4j
@Named("contentTemplate")
@ApplicationScoped
public class TemplateCommands {

    private final ConfigCommands config = new ConfigCommands();
    /** Test seam: the caller behind the current request. */
    @Setter
    private Supplier<Caller> callerSource = Caller::current;

    /**
     * The latest version of every template the caller may see, name-sorted -- drives the manager table and
     * pickers. Site staff see everything; an org's content editor sees the shared templates and their own
     * org's, never another tenant's.
     */
    public List<ContentTemplate> getTemplates() {
        try {
            final Caller caller = callerSource.get();
            return DAO.getInstance().getAllTemplates(Cached.NO).stream()
                    .filter(template -> visible(caller, template))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list templates", ex);
            return List.of();
        }
    }

    /**
     * The manager table narrowed to one kind (the "Email Templates" menu entry passes {@code ?kind=MAIL}).
     * A null, blank, or unrecognized kind answers the full list -- the empty-query-param trap means the
     * page can hand this "" verbatim.
     */
    public List<ContentTemplate> getTemplates(final String kind) {
        final TemplateKind wanted = parseKind(kind);
        if (wanted == null) {
            return getTemplates();
        }
        return getTemplates().stream().filter(template -> template.getKind() == wanted).toList();
    }

    private static TemplateKind parseKind(final String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return TemplateKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The latest version of one template, as an editable copy. Never null (bean convention): an unknown id
     * answers a blank template under that id, which is also how "New Template" starts.
     */
    public ContentTemplate getTemplate(final String id) {
        try {
            return DAO.getInstance().getTemplate(id, Cached.NO).map(ContentTemplate::copy)
                    .map(TemplateCommands::withLiveProperties)
                    .orElseGet(() -> blank(id));
        } catch (final RuntimeException ex) {
            log.error("Unable to look up template: " + id, ex);
            return blank(id);
        }
    }

    /** One specific retained version, as an editable copy (the history/restore views read these). */
    public ContentTemplate getTemplate(final String id, final int version) {
        try {
            return DAO.getInstance().getTemplate(id, version, Cached.NO).map(ContentTemplate::copy)
                    .map(TemplateCommands::withLiveProperties)
                    .orElseGet(() -> getTemplate(id));
        } catch (final RuntimeException ex) {
            log.error("Unable to look up template: " + id + " v" + version, ex);
            return blank(id);
        }
    }

    /**
     * A PROGRAMMATIC copy shows its type's LIVE properties, whatever the row stored (the stored list is a
     * creation-time snapshot -- see {@link ProgrammaticTypes#placeholdersOf}); the manager then writes
     * that list back on save, so an old row catches up the next time anyone touches it.
     */
    private static ContentTemplate withLiveProperties(final ContentTemplate copy) {
        if (copy.getKind() == TemplateKind.PROGRAMMATIC) {
            copy.setPlaceholders(ProgrammaticTypes.placeholdersOf(copy));
        }
        return copy;
    }

    /** Saves (creating or updating); {@code contentAdmin} only. Kind-specific validation applies. */
    public boolean saveTemplate(final ContentTemplate template) {
        if (template == null || template.getId() == null || template.getId().isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        normalizeScope(template);
        if (!mayAuthor(caller, template, storedTemplate(template.getId()))) {
            log.warn("Refusing template save of '{}': caller may not author it", template.getId());
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved",
                    "You may only author templates scoped to an organization whose site you edit.");
            return false;
        }
        if (template.isOrgOwned() && findOrg(template.getOrgId()) == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved",
                    "The template's organization does not exist.");
            return false;
        }
        final String problem = validateForSave(template);
        if (problem != null) {
            log.warn("Refusing template save of '{}': {}", template.getId(), problem);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", problem);
            return false;
        }
        normalizePlaceholders(template);
        template.setModifiedBy(caller.auditActor().email());
        final boolean saved;
        try {
            saved = DAO.getInstance().saveTemplate(template, retainCount());
        } catch (final RuntimeException ex) {
            log.error("Unable to save template: " + template.getId(), ex);
            return false;
        }
        if (saved) {
            warnAboutRichTextInParagraph(template);
            audit(caller, template.getId(),
                    "Saved v" + template.getVersion() + " of template '" + template.getId() + "'");
        }
        return saved;
    }

    /**
     * Who may author a template. A global contentAdmin (or site admin, via Caller) authors everything --
     * template bodies are raw HTML rendered unescaped, so this is script access. An ORG-scoped contentAdmin
     * authors only templates owned by an org whose site they edit, and may never re-scope a template into
     * (or out of) that org: the STORED row must already be that org's, or not exist -- otherwise an org
     * editor could seize a shared template, or another org's, by saving it under their own scope. Scoping
     * the grant to an org scopes the blast radius of that script access to the org's own site.
     */
    static boolean mayAuthor(final Caller caller, final ContentTemplate template, final ContentTemplate stored) {
        if (caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            return true;
        }
        if (!template.isOrgOwned() || !caller.has(PrivilegeCommands.CONTENT_ADMIN, template.getOrgId())) {
            return false;
        }
        return stored == null || template.getOrgId().equals(stored.getOrgId());
    }

    /** Whether the caller may see a template at all: everything for site staff, own-org + shared otherwise. */
    private boolean visible(final Caller caller, final ContentTemplate template) {
        return caller.has(PrivilegeCommands.CONTENT_ADMIN) || !template.isOrgOwned()
                || caller.has(PrivilegeCommands.CONTENT_ADMIN, template.getOrgId());
    }

    private static ContentTemplate storedTemplate(final String id) {
        try {
            return DAO.getInstance().getTemplate(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the stored template: " + id, ex);
            return null;
        }
    }

    /** The editor's Scope menu submits "" for "shared"; the stored shape of shared is null. */
    private static void normalizeScope(final ContentTemplate template) {
        if (template.getOrgId() != null && template.getOrgId().isBlank()) {
            template.setOrgId(null);
        }
    }

    private static Organization findOrg(final String orgId) {
        try {
            return DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.YES)
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up organization " + orgId, ex);
            return null;
        }
    }

    /**
     * The organizations a template may be scoped to, name-sorted, for the editor's Scope menu. Every org:
     * template authoring is site-staff work ({@code contentAdmin} is global), so the menu is not narrowed
     * to the request's site.
     */
    public List<Organization> getScopeChoices() {
        try {
            final Caller caller = callerSource.get();
            return DAO.getInstance().getOrganizations(Cached.YES).stream()
                    // Site staff scope to any org; an org's editor only to an org whose site they edit.
                    .filter(org -> caller.has(PrivilegeCommands.CONTENT_ADMIN)
                            || caller.has(PrivilegeCommands.CONTENT_ADMIN, org.getId().getValue()))
                    .sorted(java.util.Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list organizations for the template editor", ex);
            return List.of();
        }
    }

    /** "Shared" for a site-level template, else the owning organization's name (its id if unreadable). */
    public String scopeLabel(final ContentTemplate template) {
        if (template == null || !template.isOrgOwned()) {
            return "Shared";
        }
        final Organization org = findOrg(template.getOrgId());
        return org == null ? template.getOrgId() : org.getName();
    }

    /**
     * A WYSIWYG value is block HTML, so a body placing a RICH_TEXT token inside a {@code <p>} gets split by
     * the browser and the value renders OUTSIDE the paragraph that was meant to hold it. Advisory, not
     * blocking: the body is valid HTML and existing templates must keep saving -- but the author hears it
     * now instead of finding it in the rendered page (which is exactly how this was found).
     */
    private void warnAboutRichTextInParagraph(final ContentTemplate template) {
        final List<String> nested = RichTextRules.richTextTokensInsideParagraph(template);
        if (nested.isEmpty()) {
            return;
        }
        // The SUMMARY carries the whole warning: growl details are shown only for the URL-parameter
        // messages (see template.xhtml's hasDetail), so a detail-only explanation never reaches anyone.
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN,
                "Saved, but check the layout: rich text (" + String.join(", ", nested)
                        + ") sits inside a <p>. The editor writes paragraphs, which cannot nest, so the "
                        + "value will break out of it -- use a block container such as <div>.", "");
    }

    /**
     * Deletes a template -- refused while ANY content instance (current or retained history) still
     * references it, so a published page can never lose the template it renders with.
     */
    public boolean deleteTemplate(final String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        final ContentTemplate stored = storedTemplate(id);
        if (stored == null || !mayAuthor(caller, stored, stored)) {
            log.warn("Refusing template delete of '{}': caller may not author it", id);
            return false;
        }
        if (isReferenced(id)) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN,
                    "Not deleted", "Content still uses template '" + id + "'. Delete or re-template it first.");
            return false;
        }
        final boolean deleted;
        try {
            deleted = DAO.getInstance().deleteTemplate(id);
        } catch (final RuntimeException ex) {
            log.error("Unable to delete template: " + id, ex);
            return false;
        }
        if (deleted) {
            audit(caller, id, "Deleted template '" + id + "'");
        }
        return deleted;
    }

    /** Every retained version, current first, for the history dialog. */
    public List<ContentTemplate> getHistory(final String id) {
        try {
            return DAO.getInstance().getTemplateRecord(id, Cached.NO).map(TemplateRecord::getAllVersions)
                    .orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to load template history: " + id, ex);
            return List.of();
        }
    }

    /** Restores a retained version as the new current (itself a new version -- history stays linear). */
    public boolean restoreTemplate(final String id, final int version) {
        final TemplateRecord record;
        try {
            record = DAO.getInstance().getTemplateRecord(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to load template for restore: " + id, ex);
            return false;
        }
        final ContentTemplate snapshot = record == null ? null : record.findVersion(version);
        if (snapshot == null || record.getCurrent() == null) {
            return false;
        }
        final ContentTemplate restored = snapshot.copy();
        // Carry the stored current version so the DAO's lost-update guard accepts the save.
        restored.setVersion(record.getCurrent().getVersion());
        return saveTemplate(restored);
    }

    /**
     * Creates the three built-in templates when absent; existing ones are left alone, so this is a safe
     * bootstrap button on the manager page (and the production first-run step).
     *
     * @return how many were created.
     */
    public int installStarterTemplates() {
        final Caller caller = callerSource.get();
        if (!caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            log.warn("Refusing starter-template install: caller lacks contentAdmin");
            return 0;
        }
        int created = 0;
        for (final ContentTemplate starter : StarterTemplates.all()) {
            final boolean exists;
            try {
                exists = DAO.getInstance().getTemplate(starter.getId(), Cached.NO).isPresent();
            } catch (final RuntimeException ex) {
                log.error("Unable to check for starter template: " + starter.getId(), ex);
                continue;
            }
            if (!exists && saveTemplate(starter)) {
                created++;
            }
        }
        return created;
    }

    /**
     * Kind-specific validation. Also normalizes the kind-dependent fields (a container carries no body; a
     * programmatic template's placeholders come from its type on creation).
     *
     * @return a user-facing problem, or null when the template may be saved.
     */
    private String validateForSave(final ContentTemplate template) {
        final ContentTemplate stored;
        try {
            stored = DAO.getInstance().getTemplate(template.getId(), Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to check the stored template for: " + template.getId(), ex);
            return "The existing template could not be checked; try again.";
        }
        if (stored != null && stored.getKind() != template.getKind()) {
            return "A template's kind cannot change after creation; create a new template instead.";
        }
        return switch (template.getKind()) {
            case STANDARD, MAIL -> HtmlFragmentValidator.validate(template.getBody());
            case CONTAINER -> normalizeContainer(template);
            case PROGRAMMATIC -> normalizeProgrammatic(template);
        };
    }

    private String normalizeContainer(final ContentTemplate template) {
        if (template.getMaxChildren() != null && template.getMaxChildren() < 1) {
            return "Max children must be blank (unlimited) or at least 1.";
        }
        for (final String childId : allowedIds(template)) {
            final ContentTemplate child = DAO.getInstance().getTemplate(childId, Cached.NO).orElse(null);
            if (child != null && child.getKind() == TemplateKind.CONTAINER) {
                return "A container may not allow another container ('" + childId + "') as a child.";
            }
        }
        // A container's body is the row it wraps around EACH child (optionally inside a {{children:start}}
        // / {{children:end}} region carrying a wrapper). Blank is legal and means the built-in row.
        final String body = template.getBody();
        final String unusable = ContentRenderer.containerBodyProblem(body);
        if (unusable != null) {
            return unusable;
        }
        if (body != null && !body.isBlank()) {
            final String invalid = HtmlFragmentValidator.validate(body);
            if (invalid != null) {
                return invalid;
            }
        }
        // A container still has no placeholders of its own: it reads the CHILD's properties instead.
        template.setPlaceholders(List.of());
        return null;
    }

    private String normalizeProgrammatic(final ContentTemplate template) {
        final ProgrammaticContentTemplate type =
                ProgrammaticTypes.byId(template.getProgrammaticTypeId()).orElse(null);
        if (type == null) {
            return "Unknown programmatic type: '" + template.getProgrammaticTypeId() + "'.";
        }
        // The type's property list IS the placeholder list, on every save and not only on creation: the
        // stored copy is advisory (every reader goes through ProgrammaticTypes.placeholdersOf), so a save
        // is the moment a row written against an older registry catches up.
        template.setPlaceholders(type.getProperties());
        template.setBody("");
        return null;
    }

    private static List<String> allowedIds(final ContentTemplate template) {
        return template.getAllowedChildTemplateIds() == null
                ? List.of() : template.getAllowedChildTemplateIds();
    }

    /** The template kinds, for the New Template dialog's chooser. */
    public List<TemplateKind> getKinds() {
        return List.of(TemplateKind.values());
    }

    /** The registered programmatic types, for the New Template dialog's type picker. */
    public List<ProgrammaticContentTemplate> getProgrammaticTypes() {
        return ProgrammaticTypes.ALL;
    }

    /** What a container may allow as children: every template that is not itself a container. */
    public List<ContentTemplate> getChildTemplateChoices() {
        return getTemplates().stream()
                .filter(template -> template.getKind() != TemplateKind.CONTAINER)
                .toList();
    }

    /**
     * The placeholder types, for the type menu (bound at render time, per the dialog rules). CHOICE is
     * excluded: it only means something with a programmatic type's options provider behind it.
     */
    public List<Placeholder.Type> getPlaceholderTypes() {
        return Arrays.stream(Placeholder.Type.values())
                .filter(type -> !Placeholder.isProviderBacked(type))
                .toList();
    }

    /** Appends an empty placeholder row for the dialog's Add button (JSFT expressions cannot use new). */
    public void addPlaceholder(final ContentTemplate template) {
        if (template != null) {
            template.getPlaceholders().add(new Placeholder("", Placeholder.Type.TEXT, "", null, false));
        }
    }

    /** Removes one placeholder row, for the dialog's per-row remove button. */
    public void removePlaceholder(final ContentTemplate template, final Placeholder placeholder) {
        if (template != null) {
            template.getPlaceholders().remove(placeholder);
        }
    }

    /** Dialog rows arrive as raw typed text: trim names, drop nameless rows, default missing types. */
    private static void normalizePlaceholders(final ContentTemplate template) {
        final List<Placeholder> cleaned = new ArrayList<>();
        for (final Placeholder ph : template.getPlaceholders()) {
            final Placeholder normalized =
                    new Placeholder(ph.getName(), ph.getType(), ph.getLabel(), ph.getHint(), ph.isRequired());
            if (!normalized.getName().isEmpty()) {
                cleaned.add(normalized);
            }
        }
        template.setPlaceholders(cleaned);
    }

    /**
     * Scans the body for <code>{{tokens}}</code> and appends a TEXT placeholder for each one not yet
     * declared -- the "Detect from body" button. Existing declarations are never modified.
     */
    public void detectPlaceholders(final ContentTemplate template) {
        if (template == null) {
            return;
        }
        final Set<String> present = ContentRenderer.tokenNames(template.getBody());
        final List<Placeholder> declared = template.getPlaceholders();
        for (final String name : present) {
            if (declared.stream().noneMatch(ph -> ph.getName().equals(name))) {
                declared.add(new Placeholder(name, Placeholder.Type.TEXT, name, null, false));
            }
        }
    }

    private boolean isReferenced(final String templateId) {
        try {
            return DAO.getInstance().getAllContentRecords(Cached.NO).stream()
                    .map(ContentRecord::getAllVersions)
                    .flatMap(List::stream)
                    .map(ContentInstance::getTemplateId)
                    .anyMatch(templateId::equals);
        } catch (final RuntimeException ex) {
            // When the check itself fails, refuse the delete: wrongly keeping a template is recoverable,
            // wrongly deleting one breaks published pages.
            log.error("Unable to check template references; refusing delete of " + templateId, ex);
            return true;
        }
    }

    private int retainCount() {
        return config.getInt(KnownSettings.CONTENT_VERSIONS_RETAINED, 0, 50);
    }

    private void audit(final Caller caller, final String id, final String message) {
        Audit.builder(AuditAction.TEMPLATE, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_TEMPLATE, id)
                .message(message)
                .log();
    }

    private static ContentTemplate blank(final String id) {
        return new ContentTemplate(id == null || id.isBlank() ? "" : id.trim(), 0, "", "", "",
                new ArrayList<>(), null, null);
    }
}
