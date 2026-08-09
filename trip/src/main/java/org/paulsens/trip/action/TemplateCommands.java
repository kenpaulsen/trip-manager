package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateRecord;

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

    /** The latest version of every template, name-sorted -- drives the manager table and pickers. */
    public List<ContentTemplate> getTemplates() {
        try {
            return DAO.getInstance().getAllTemplates();
        } catch (final RuntimeException ex) {
            log.error("Unable to list templates", ex);
            return List.of();
        }
    }

    /**
     * The latest version of one template, as an editable copy. Never null (bean convention): an unknown id
     * answers a blank template under that id, which is also how "New Template" starts.
     */
    public ContentTemplate getTemplate(final String id) {
        try {
            return DAO.getInstance().getTemplate(id).map(ContentTemplate::copy)
                    .orElseGet(() -> blank(id));
        } catch (final RuntimeException ex) {
            log.error("Unable to look up template: " + id, ex);
            return blank(id);
        }
    }

    /** One specific retained version; the content dialog uses this to build its placeholder form. */
    public ContentTemplate getTemplate(final String id, final int version) {
        try {
            return DAO.getInstance().getTemplate(id, version).map(ContentTemplate::copy)
                    .orElseGet(() -> getTemplate(id));
        } catch (final RuntimeException ex) {
            log.error("Unable to look up template: " + id + " v" + version, ex);
            return blank(id);
        }
    }

    /** Saves (creating or updating); {@code contentAdmin} only. */
    public boolean saveTemplate(final ContentTemplate template) {
        if (template == null || template.getId() == null || template.getId().isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            log.warn("Refusing template save of '{}': caller lacks contentAdmin", template.getId());
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
            audit(caller, template.getId(),
                    "Saved v" + template.getVersion() + " of template '" + template.getId() + "'");
        }
        return saved;
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
        if (!caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            log.warn("Refusing template delete of '{}': caller lacks contentAdmin", id);
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
            return DAO.getInstance().getTemplateRecord(id).map(TemplateRecord::getAllVersions)
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
            record = DAO.getInstance().getTemplateRecord(id).orElse(null);
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
                exists = DAO.getInstance().getTemplate(starter.getId()).isPresent();
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

    /** The placeholder types, for the type menu (bound at render time, per the dialog rules). */
    public List<Placeholder.Type> getPlaceholderTypes() {
        return List.of(Placeholder.Type.values());
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
            return DAO.getInstance().getAllContentRecords().stream()
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
