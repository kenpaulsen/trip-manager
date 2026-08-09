package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;

/**
 * Template-driven page content, exposed to pages as {@code #{content}}.
 *
 * <p>A page renders a section with {@code #{content.getForSection('home.events')}} plus
 * {@code #{content.render(c)}} per instance; editing goes through the shared content dialog. Who may edit
 * what: {@code contentAdmin} edits every section; {@link #SECTION_EDIT_PRIVS} grants narrower privileges per
 * section ({@code eventAdmin} for the home Events list). The save/delete paths re-check server-side -- the
 * page's {@code rendered=} gate only hides buttons.
 */
@Slf4j
@Named("content")
@ApplicationScoped
public class ContentCommands {

    /** Sections editable by a privilege narrower than {@code contentAdmin}. */
    private static final Map<String, String> SECTION_EDIT_PRIVS =
            Map.of("home.events", PrivilegeCommands.EVENT_ADMIN);

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

    /** Saves (creating or updating); refused without edit rights on the instance's section. */
    public boolean saveContent(final ContentInstance instance) {
        if (instance == null || instance.getSection() == null || instance.getSection().isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!mayEdit(caller, instance.getSection())) {
            log.warn("Refusing content save in '{}': caller lacks edit rights", instance.getSection());
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
        if (saved) {
            audit(caller, instance.getId(),
                    "Saved v" + instance.getVersion() + " of '" + instance.getTitle() + "' in "
                            + instance.getSection());
        }
        return saved;
    }

    /** Deletes an instance (all versions); refused without edit rights on its section. */
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
        final boolean deleted = DAO.getInstance().deleteContent(id);
        if (deleted) {
            audit(caller, id, "Deleted '" + existing.getTitle() + "' from " + existing.getSection());
        }
        return deleted;
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
     * The rendered HTML for one instance: its pinned template version, falling back to the template's latest
     * when that version has aged out of retention, and to nothing when the template is gone entirely.
     */
    public String render(final ContentInstance instance) {
        if (instance == null || instance.getTemplateId() == null) {
            return "";
        }
        try {
            final ContentTemplate template = DAO.getInstance()
                    .getTemplate(instance.getTemplateId(), instance.getTemplateVersion())
                    .or(() -> DAO.getInstance().getTemplate(instance.getTemplateId()))
                    .orElse(null);
            return template == null ? "" : ContentRenderer.render(template, instance);
        } catch (final RuntimeException ex) {
            // A public page must render without this block rather than fail with it.
            log.error("Unable to render content: " + instance.getId(), ex);
            return "";
        }
    }

    /**
     * The page-side gate for edit buttons: {@code #{content.canEdit('home.events', userId)}}. A site
     * administrator passes outright, mirroring the save path's {@link Caller#has} short-circuit -- without
     * it an admin sees no buttons until someone hand-creates the privilege rows.
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
        final String sectionPriv = SECTION_EDIT_PRIVS.get(section);
        return sectionPriv != null && priv.check(sectionPriv, null, userId);
    }

    private boolean mayEdit(final Caller caller, final String section) {
        if (caller.has(PrivilegeCommands.CONTENT_ADMIN)) {
            return true;
        }
        final String sectionPriv = SECTION_EDIT_PRIVS.get(section);
        return sectionPriv != null && caller.has(sectionPriv);
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
