package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.HtmlCheck;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.ProgrammaticContentTemplate;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.RichTextRules;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.TemplateRecord;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.Util;

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

    /** The most tags a template keeps, and the longest one: search words, not sentences. */
    static final int MAX_TAGS = 12;
    static final int MAX_TAG_LENGTH = 30;

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
            // A language version is listed under its template (versionsOf), never as a row of its own.
            return DAO.getInstance().getAllTemplates(Cached.NO).stream()
                    .filter(template -> visible(caller, template))
                    .filter(template -> PageVersions.languageOf(template.getId()).isEmpty())
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
            PageFeedback.error(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.templateCommands.authorOwnOrgOnly"));
            return false;
        }
        if (template.isOrgOwned() && findOrg(template.getOrgId()) == null) {
            PageFeedback.error(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.templateCommands.orgMissing"));
            return false;
        }
        if (!sameScopeAsItsTemplate(template)) {
            log.warn("Refusing template save of '{}': a language version keeps its template's scope",
                    template.getId());
            PageFeedback.error(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.templateCommands.versionScopeFixed"));
            return false;
        }
        final String problem = validateForSave(template);
        if (problem != null) {
            log.warn("Refusing template save of '{}': {}", template.getId(), problem);
            PageFeedback.error(Text.get("feedback.shared.notSaved"), problem);
            return false;
        }
        normalizePlaceholders(template);
        normalizeListing(template);
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

    /**
     * {@link #mayAuthor(Caller, ContentTemplate, ContentTemplate)} for a STORED row, for the manager page's
     * per-row Edit / History / Delete gates: a row the caller may not author gets no editing affordance at
     * all (the save would only fail with a growl), and a shared row instead offers {@link #copyForOrg}.
     */
    public boolean mayAuthor(final ContentTemplate stored) {
        return stored != null && mayAuthor(callerSource.get(), stored, stored);
    }

    /**
     * The organization scope the manager page is working in: the {@code ?orgId=} it was opened with (the
     * org hub's Templates card), else the organization whose site the request is on, else "" for the
     * site-wide page. One place so the page never has to spell the fallback out twice.
     */
    public String orgScope(final String orgIdParam) {
        final String explicit = Util.trimToNull(orgIdParam);
        if (explicit != null) {
            return explicit;
        }
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? site.orgId().getValue() : "";
    }

    /** The scope organization's name, for the page's labels; "" when there is no org scope. */
    public String orgScopeName(final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final Organization org = (scope == null) ? null : findOrg(scope);
        return org == null ? "" : org.getName();
    }

    /**
     * The manager's rows for one kind in one scope, ONE ROW PER USE CASE.
     *
     * <p>In an ORGANIZATION's scope a use case the org has customized shows the org's OWN row (badged,
     * revertable) and the shared original is not listed beside it: two rows for one email would be a
     * standing invitation to edit the one that is never sent. Uncustomized use cases show the shared row,
     * read-only, offering {@link #customize}. The org's own templates that customize nothing (created by
     * the org itself) list as themselves.
     *
     * <p>The SITE-WIDE page (a blank scope) is for the GLOBAL templates: org-owned rows are hidden there,
     * because a site admin editing "Registration received (Acme)" from the shared page would be editing
     * one tenant's copy while believing they were fixing everyone's.
     */
    public List<ContentTemplate> getTemplatesFor(final String kind, final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final List<ContentTemplate> visible = getTemplates();
        final List<ContentTemplate> scoped = (scope == null) ? sharedOnly(visible) : oneRowPerUseCase(visible, scope);
        final TemplateKind wanted = parseKind(kind);
        return wanted == null ? scoped : scoped.stream().filter(t -> t.getKind() == wanted).toList();
    }

    private static List<ContentTemplate> sharedOnly(final List<ContentTemplate> templates) {
        return templates.stream().filter(template -> !template.isOrgOwned()).toList();
    }

    private List<ContentTemplate> oneRowPerUseCase(final List<ContentTemplate> visible, final String orgId) {
        final Organization org = findOrg(orgId);
        if (org == null) {
            return sharedOnly(visible);
        }
        final Map<String, ContentTemplate> mine = new HashMap<>();
        for (final ContentTemplate template : visible) {
            if (orgId.equals(template.getOrgId())) {
                mine.put(template.getId(), template);
            }
        }
        final List<ContentTemplate> rows = new ArrayList<>();
        final Set<String> customizations = new HashSet<>();
        for (final ContentTemplate shared : sharedOnly(visible)) {
            final ContentTemplate own = mine.get(orgCopyId(shared.getId(), org));
            rows.add(own == null ? shared : own);
            if (own != null) {
                customizations.add(own.getId());
            }
        }
        // An org template that customizes nothing -- one the org authored itself -- is still its own row.
        mine.values().stream().filter(t -> !customizations.contains(t.getId())).forEach(rows::add);
        rows.sort(Comparator.comparing(ContentTemplate::getName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rows;
    }

    /**
     * The template a managed id resolves to for one organization: the org's own customization when it has
     * one, else the shared row. This is THE resolution order, and both the manager page and every mail
     * sender go through it, so what an editor sees and what goes out can never disagree.
     *
     * <p>A null/blank organization (background work with no entity to take one from) answers the shared
     * row, which is also what an org with no customization gets. Never null-safe by accident: a row under
     * the copy's id that belongs to somebody ELSE is ignored, so no tenant can be served another's copy.
     */
    public ContentTemplate resolveForOrg(final String templateId, final String orgId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        final ContentTemplate customization = orgCustomizationOf(templateId, orgId);
        return customization == null ? storedTemplate(templateId) : customization;
    }

    /**
     * {@link #resolveForOrg(String, String)} for a reader of one language: the email's version in that language
     * when one is written ({@code docs/content-templates.md}, "Language versions of an email"). In order:
     *
     * <ol>
     *   <li>the organization's own version in the language ({@code {id}-{slug}@es});</li>
     *   <li>the organization's own copy ({@code {id}-{slug}}): an organization's wording beats the site's
     *       translation of words it chose to replace;</li>
     *   <li>the shared version in the language ({@code {id}@es});</li>
     *   <li>the shared row.</li>
     * </ol>
     *
     * <p>A template's own row is written in English, so English, a null locale and a language this build does not
     * speak all resolve exactly as the two-argument form. Every level checks its OWNER as well as its id: an
     * organization's version is served only to that organization, and a "shared" version some organization owns
     * is served to nobody.
     */
    public ContentTemplate resolveForOrg(final String templateId, final String orgId, final Locale locale) {
        final Language language = versionLanguage(locale);
        if (templateId == null || templateId.isBlank() || language == null) {
            return resolveForOrg(templateId, orgId);
        }
        final String scope = Util.trimToNull(orgId);
        final Organization org = (scope == null) ? null : findOrg(scope);
        if (org != null) {
            final ContentTemplate own = ownedBy(scope,
                    storedTemplate(PageVersions.keyFor(orgCopyId(templateId, org), language)));
            final ContentTemplate copy =
                    (own != null) ? own : ownedBy(scope, storedTemplate(orgCopyId(templateId, org)));
            if (copy != null) {
                return copy;
            }
        }
        final ContentTemplate shared = storedTemplate(PageVersions.keyFor(templateId, language));
        return (shared != null && !shared.isOrgOwned()) ? shared : storedTemplate(templateId);
    }

    /** The language a reader's locale asks a version in; null for English (the rows' own) and unknown ones. */
    private static Language versionLanguage(final Locale locale) {
        final Language language = (locale == null) ? null : Language.forTag(locale.getLanguage()).orElse(null);
        return (language == Language.English) ? null : language;
    }

    private static ContentTemplate ownedBy(final String orgId, final ContentTemplate template) {
        return (template != null && orgId.equals(template.getOrgId())) ? template : null;
    }

    /** The org's own copy of {@code templateId}, or null when it has none (or the row is not really its). */
    private ContentTemplate orgCustomizationOf(final String templateId, final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final Organization org = (scope == null) ? null : findOrg(scope);
        if (org == null) {
            return null;
        }
        final ContentTemplate copy = storedTemplate(orgCopyId(templateId, org));
        return (copy != null && scope.equals(copy.getOrgId())) ? copy : null;
    }

    /**
     * The id an organization's copy of {@code templateId} takes: {@code {id}-{slug}} for a hosted org (the
     * convention the manager has always shown), and {@code {id}-{orgUUID}} for an org with no subdomain --
     * an org reached from its hub need not be hosted to customize the email sent on its behalf. Derived,
     * never stored, so the copy and the sender always agree on where to look.
     */
    static String orgCopyId(final String templateId, final Organization org) {
        return templateId + "-" + copySuffix(org);
    }

    private static String copySuffix(final Organization org) {
        final String slug = org.getSlug();
        return (slug == null || slug.isBlank()) ? org.getId().getValue() : slug;
    }

    /**
     * The SHARED template a row customizes, or null when the row is not a customization at all (a shared
     * row, or an org template the org authored itself). Read off the id convention rather than a stored
     * pointer, so a hand-created row that follows the convention behaves like any other copy.
     */
    public ContentTemplate siteDefault(final ContentTemplate stored) {
        if (stored == null || !stored.isOrgOwned()) {
            return null;
        }
        final Organization org = findOrg(stored.getOrgId());
        final String suffix = (org == null) ? null : "-" + copySuffix(org);
        if (suffix == null || !stored.getId().endsWith(suffix) || stored.getId().length() <= suffix.length()) {
            return null;
        }
        final ContentTemplate shared =
                storedTemplate(stored.getId().substring(0, stored.getId().length() - suffix.length()));
        return (shared != null && !shared.isOrgOwned()) ? shared : null;
    }

    /** Whether a row is an organization's customization of a shared use case -- the "Customized" badge. */
    public boolean isCustomization(final ContentTemplate stored) {
        return siteDefault(stored) != null;
    }

    /** {@link #siteDefault} by id, for the read-only "View site default" dialog. Null when there is none. */
    public ContentTemplate siteDefaultOf(final String templateId) {
        return (templateId == null || templateId.isBlank()) ? null : siteDefault(storedTemplate(templateId));
    }

    /**
     * Whether the page should offer "Customize" on a row: an organization scope, a SHARED row (another
     * tenant's is never a source -- no cross-org sharing), no copy yet, and a caller who may author
     * templates for that org (site staff included, so an admin can seed an org's copy for them).
     */
    public boolean mayCustomize(final ContentTemplate stored, final String orgId) {
        final String scope = Util.trimToNull(orgId);
        if (stored == null || scope == null || stored.isOrgOwned()
                || !callerSource.get().has(PrivilegeCommands.CONTENT_ADMIN, scope)) {
            return false;
        }
        final Organization org = findOrg(scope);
        return org != null && storedTemplate(orgCopyId(stored.getId(), org)) == null;
    }

    /**
     * Clones a SHARED template into an organization's scope so the org can customize it without touching
     * the shared original (an org editor may not author a shared template, and must not seize it by
     * re-scoping). The copy is {@link #orgCopyId} at version 1 with the same kind, body, placeholders and
     * container/programmatic settings, and it is authorized like any other write: {@link #mayAuthor} on the
     * RESULT's scope.
     *
     * <p>MAIL templates are copied too, and that is the whole point: {@link #resolveForOrg} is what every
     * sender resolves through, so an org's copy of {@code registration-received} is the mail its
     * registrants actually get. (Before per-org resolution existed a mail copy would have been dead copy,
     * so this refused MAIL.)
     *
     * @return the copy's id, or "" when nothing was copied; every refusal explains itself with a growl.
     */
    public String customize(final String id, final String orgId) {
        final String scope = Util.trimToNull(orgId);
        if (id == null || id.isBlank()) {
            return "";
        }
        if (scope == null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedNoOrg"));
        }
        final ContentTemplate source = storedTemplate(id);
        if (source == null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedNoTemplate", id));
        }
        if (source.isOrgOwned()) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedNotShared"));
        }
        final Organization org = findOrg(scope);
        if (org == null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedOrgUnread"));
        }
        return saveCustomization(source, org, callerSource.get());
    }

    private String saveCustomization(final ContentTemplate source, final Organization org, final Caller caller) {
        final ContentTemplate copy = orgCopy(source, org);
        if (!mayAuthor(caller, copy, null)) {
            log.warn("Refusing template copy of '{}' for org {}: caller may not author it",
                    source.getId(), org.getId());
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedNotYours"));
        }
        if (storedTemplate(copy.getId()) != null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedExists", copy.getId()));
        }
        if (!saveTemplate(copy)) {
            return "";
        }
        audit(caller, copy.getId(), "Copied shared template '" + source.getId() + "' as '" + copy.getId()
                + "' for organization '" + org.getName() + "'");
        copyVersions(source, copy);
        return copy.getId();
    }

    /**
     * Customizing an email takes its shared language versions along. Without them the organization's English copy
     * would outrank the site's translation ({@link #resolveForOrg(String, String, Locale)}), and customizing the
     * English wording would quietly switch every Spanish reader to English.
     */
    private void copyVersions(final ContentTemplate source, final ContentTemplate copy) {
        for (final Language language : otherLanguages()) {
            final ContentTemplate version = storedTemplate(PageVersions.keyFor(source.getId(), language));
            if (version != null && !version.isOrgOwned()
                    && storedTemplate(PageVersions.keyFor(copy.getId(), language)) == null) {
                saveTemplate(versionCopy(version, PageVersions.keyFor(copy.getId(), language), copy.getOrgId()));
            }
        }
    }

    /** A new row holding {@code source}'s wording under {@code id}, in {@code orgId}'s scope. */
    private static ContentTemplate versionCopy(final ContentTemplate source, final String id, final String orgId) {
        final ContentTemplate copy = source.copy();
        copy.setId(id);
        copy.setVersion(0);
        copy.setOrgId(orgId);
        copy.setModified(null);
        copy.setModifiedBy(null);
        return copy;
    }

    private static List<Language> otherLanguages() {
        return Arrays.stream(Language.values()).filter(language -> language != Language.English).toList();
    }

    /**
     * Whether a language version is in the same scope as the template it translates. The scope is the
     * template's, never chosen per version: a "shared" version saved into one organization would be that
     * organization's wording under a shared id, and an organization's editor could otherwise claim a shared
     * template's language by saving its version id into their own scope first.
     */
    private static boolean sameScopeAsItsTemplate(final ContentTemplate template) {
        if (PageVersions.languageOf(template.getId()).isEmpty()) {
            return true;
        }
        // No template under the base id: refused. Allowed, an organization's editor could claim another
        // organization's (or the shared) version id before that template exists, and its owner could then
        // never add that language (the id is taken) while its readers kept reading English.
        final ContentTemplate base = storedTemplate(PageVersions.baseOf(template.getId()));
        return base != null && Objects.equals(base.getOrgId(), template.getOrgId());
    }

    // ================================================================ language versions on the manager page

    /**
     * One language an email template may be read in, as its row on the manager page shows it: written or not,
     * and what the caller may do about it.
     */
    @Getter
    public static final class LanguageVersion {
        private final Language language;
        /** The version's id, {@code {templateId}@{tag}}. */
        private final String id;
        /** A version in the template's scope exists under {@link #id}. */
        private final boolean written;
        /** The caller may edit and delete the written version. */
        private final boolean editable;
        /** Not written yet, and the caller may author the template, so it may start one. */
        private final boolean addable;

        LanguageVersion(final Language language, final String id, final boolean written, final boolean editable,
                final boolean addable) {
            this.language = language;
            this.id = id;
            this.written = written;
            this.editable = editable;
            this.addable = addable;
        }

        public String getLanguageName() {
            return language.getDisplayName();
        }

        public String getTag() {
            return language.getTag();
        }
    }

    /**
     * The languages an email template may have versions in, on the manager page working for {@code orgId} (blank
     * for the site-wide page): every language that site offers except English, the language a template's own row
     * is written in. A site offering English alone answers nothing, so its page shows nothing new.
     */
    public List<Language> versionLanguages(final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final Organization org = (scope == null) ? null : findOrg(scope);
        return config.siteLanguages(org).stream()
                .filter(language -> language != Language.English)
                .toList();
    }

    /**
     * A manager row's language versions, one per {@link #versionLanguages} language. Only an EMAIL template has
     * them (page content is translated by page versions, {@code PageVersions}). A version counts as written only
     * when it is in the row's own scope, so a row someone else owns under the version's id is neither shown nor
     * overwritten.
     */
    public List<LanguageVersion> versionsOf(final ContentTemplate row, final String orgId) {
        if (row == null || row.getKind() != TemplateKind.MAIL || PageVersions.languageOf(row.getId()).isPresent()) {
            return List.of();
        }
        final Caller caller = callerSource.get();
        final boolean mayAuthorRow = mayAuthor(caller, row, row);
        final List<LanguageVersion> versions = new ArrayList<>();
        for (final Language language : versionLanguages(orgId)) {
            final String id = PageVersions.keyFor(row.getId(), language);
            final ContentTemplate stored = cachedTemplate(id);
            final boolean written = stored != null && Objects.equals(stored.getOrgId(), row.getOrgId());
            versions.add(new LanguageVersion(language, id, written, written && mayAuthor(caller, stored, stored),
                    stored == null && mayAuthorRow));
        }
        return versions;
    }

    /**
     * "Add a version in Español": a new row under {@code {templateId}@{tag}} starting as a copy of the template,
     * in the template's scope, for the author to translate (the {@link #customize} pattern). Only an email
     * template, only a language the page's site offers, and only for a caller who may author the template.
     *
     * @return the version's id, or "" when nothing was added; every refusal explains itself with a growl.
     */
    public String addVersion(final String templateId, final String tag, final String orgId) {
        final Language language = Language.forTag(tag).filter(versionLanguages(orgId)::contains).orElse(null);
        if (language == null) {
            return refuseCopy(Text.get("feedback.templateCommands.versionNotOffered", tag));
        }
        final ContentTemplate source = (templateId == null || templateId.isBlank()) ? null : storedTemplate(templateId);
        if (source == null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedNoTemplate", templateId));
        }
        if (source.getKind() != TemplateKind.MAIL || PageVersions.languageOf(source.getId()).isPresent()) {
            return refuseCopy(Text.get("feedback.templateCommands.versionNotMail"));
        }
        final Caller caller = callerSource.get();
        if (!mayAuthor(caller, source, source)) {
            return refuseCopy(Text.get("feedback.templateCommands.versionNotYours"));
        }
        final String id = PageVersions.keyFor(source.getId(), language);
        if (storedTemplate(id) != null) {
            return refuseCopy(Text.get("feedback.templateCommands.notCopiedExists", id));
        }
        if (!saveTemplate(versionCopy(source, id, source.getOrgId()))) {
            return "";
        }
        audit(caller, id, "Added the " + language.getTag() + " version of template '" + source.getId() + "'");
        return id;
    }

    /**
     * Drops an organization's customization so the use case falls back to the shared row -- "Revert to site
     * default". Only ever the org's OWN copy of a shared template: an org template with no site default
     * behind it would leave the use case with nothing, so that is refused rather than silently destructive.
     * Authorization and the still-referenced guard are {@link #deleteTemplate}'s, unchanged.
     */
    public boolean revertToSiteDefault(final String id, final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final ContentTemplate stored = (id == null || id.isBlank()) ? null : storedTemplate(id);
        if (stored == null || scope == null || !scope.equals(stored.getOrgId())) {
            return false;
        }
        if (siteDefault(stored) == null) {
            PageFeedback.error(Text.get("feedback.templateCommands.notReverted", id));
            return false;
        }
        // deleteTemplate takes the organization's language versions too: kept, they would still outrank the site
        // default's translations for their readers, and revert means the site's wording in every language.
        return deleteTemplate(id);
    }

    /**
     * The org-scoped clone of a shared template: a new id, everything else carried over.
     *
     * <p>The name gains a "({org})" suffix so a raw listing or an audit line says whose copy it is -- for
     * every kind EXCEPT MAIL, where the template's NAME IS THE SUBJECT LINE and the suffix would ship in
     * the subject of every email the org sends. In the org's own list the scope is the label, so nothing
     * is lost there either way.
     */
    static ContentTemplate orgCopy(final ContentTemplate source, final Organization org) {
        final ContentTemplate copy = source.copy();
        copy.setId(orgCopyId(source.getId(), org));
        copy.setVersion(0);
        if (source.getKind() != TemplateKind.MAIL) {
            copy.setName(source.getName() + " (" + org.getName() + ")");
        }
        copy.setOrgId(org.getId().getValue());
        copy.setModified(null);
        copy.setModifiedBy(null);
        return copy;
    }

    private static String refuseCopy(final String message) {
        return PageFeedback.refuseAction(message);
    }

    /** Whether the caller may see a template at all: everything for site staff, own-org + shared otherwise. */
    private boolean visible(final Caller caller, final ContentTemplate template) {
        return caller.has(PrivilegeCommands.CONTENT_ADMIN) || !template.isOrgOwned()
                || caller.has(PrivilegeCommands.CONTENT_ADMIN, template.getOrgId());
    }

    /** A row for DISPLAY (the manager's version links), read through the cache a save invalidates. */
    private static ContentTemplate cachedTemplate(final String id) {
        try {
            return DAO.getInstance().getTemplate(id, Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the template: " + id, ex);
            return null;
        }
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
                    .sorted(Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list organizations for the template editor", ex);
            return List.of();
        }
    }

    /** "Shared" for a site-level template, else the owning organization's name (its id if unreadable). */
    public String scopeLabel(final ContentTemplate template) {
        if (template == null || !template.isOrgOwned()) {
            return Text.get("feedback.templateCommands.scopeShared");
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
        PageFeedback.warn(Text.get("feedback.templateCommands.richTextInParagraph", String.join(", ", nested)));
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
            PageFeedback.warn(Text.get("feedback.templateCommands.notDeleted"),
                    Text.get("feedback.templateCommands.stillUsed", id));
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
            deleteVersionsOf(stored);
        }
        return deleted;
    }

    /**
     * A deleted template's language versions in its own scope ({@code {id}@es}): kept, they would still reach
     * their readers (resolveForOrg serves a version without asking for its template), and no screen lists them.
     */
    private void deleteVersionsOf(final ContentTemplate deleted) {
        if (PageVersions.languageOf(deleted.getId()).isPresent()) {
            return;
        }
        for (final Language language : otherLanguages()) {
            final ContentTemplate version = storedTemplate(PageVersions.keyFor(deleted.getId(), language));
            if (version != null && Objects.equals(deleted.getOrgId(), version.getOrgId())) {
                deleteTemplate(version.getId());
            }
        }
    }

    /**
     * Every retained version, current first, for the history dialog; nothing for a template the caller may not
     * see. The id arrives as a request parameter, so the listing's {@link #visible} filter has to be asked
     * again here: without it another organization's wording was one edited parameter away.
     */
    public List<ContentTemplate> getHistory(final String id) {
        final Caller caller = callerSource.get();
        try {
            return DAO.getInstance().getTemplateRecord(id, Cached.NO)
                    .filter(record -> record.getCurrent() != null && visible(caller, record.getCurrent()))
                    .map(TemplateRecord::getAllVersions)
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
            return Text.get("feedback.templateCommands.checkFailed");
        }
        if (stored != null && stored.getKind() != template.getKind()) {
            return Text.get("feedback.templateCommands.kindFixed");
        }
        return switch (template.getKind()) {
            case STANDARD, MAIL -> HtmlCheck.templateProblem(template.getBody());
            case CONTAINER -> normalizeContainer(template);
            case PROGRAMMATIC -> normalizeProgrammatic(template);
        };
    }

    private String normalizeContainer(final ContentTemplate template) {
        if (template.getMaxChildren() != null && template.getMaxChildren() < 1) {
            return Text.get("feedback.templateCommands.maxChildrenRule");
        }
        for (final String childId : allowedIds(template)) {
            final ContentTemplate child = DAO.getInstance().getTemplate(childId, Cached.NO).orElse(null);
            if (child != null && child.getKind() == TemplateKind.CONTAINER) {
                return Text.get("feedback.templateCommands.containerInContainer", childId);
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
            final String invalid = HtmlCheck.templateProblem(body);
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
            return Text.get("feedback.templateCommands.unknownType", template.getProgrammaticTypeId());
        }
        // The type's property list IS the placeholder list, on every save and not only on creation: the
        // stored copy is advisory (every reader goes through ProgrammaticTypes.placeholdersOf), so a save
        // is the moment a row written against an older registry catches up. Its labels and hints come out in
        // the SAVER's language and are stored that way on purpose: nobody reads them back while the type is
        // registered (placeholdersOf answers the live list, in the reader's language), so translating the
        // snapshot would buy nothing; it is only a last resort for a row whose type a later build dropped.
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
            final Placeholder normalized = new Placeholder(ph.getName(), ph.getType(), ph.getLabel(), ph.getHint(),
                    ph.isRequired(), ph.getSample());
            if (!normalized.getName().isEmpty()) {
                cleaned.add(normalized);
            }
        }
        template.setPlaceholders(cleaned);
    }

    /**
     * What the Add dialog reads about a template besides its name: the search tags, cleaned, and the two "where
     * it fits" flags, cleared where they cannot mean anything. A MAIL template is never in a picker; a container
     * can never sit inside a group, so it cannot be "only inside a group".
     */
    private static void normalizeListing(final ContentTemplate template) {
        if (template.getKind() == TemplateKind.MAIL) {
            template.setTags(null);
            template.setChildOnly(false);
            template.setFullWidth(false);
            return;
        }
        template.setTags(normalizeTags(template.getTags()));
        if (template.getKind() == TemplateKind.CONTAINER) {
            template.setChildOnly(false);
        }
    }

    /**
     * Tags as stored: trimmed, lower-case, inner whitespace collapsed, no blanks, no repeats (first spelling
     * wins its place), each cut to {@link #MAX_TAG_LENGTH} and the list to {@link #MAX_TAGS}. The editor's chips
     * are free-typed, so this is the only place the shape is decided.
     */
    static List<String> normalizeTags(final List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        final Set<String> cleaned = new LinkedHashSet<>();
        for (final String tag : tags) {
            final String normalized = normalizeTag(tag);
            if (!normalized.isEmpty()) {
                cleaned.add(normalized);
            }
        }
        return cleaned.stream().limit(MAX_TAGS).toList();
    }

    private static String normalizeTag(final String tag) {
        if (tag == null) {
            return "";
        }
        final String collapsed = tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return collapsed.length() > MAX_TAG_LENGTH ? collapsed.substring(0, MAX_TAG_LENGTH).trim() : collapsed;
    }

    /**
     * Autocomplete for the editor's Tags chips: what was typed, as it would be stored, FIRST, then the tags
     * already in use on the templates the caller may see that contain it, those that start with it before the
     * rest (all of them, alphabetically, for an empty query). The typed word leads so that Enter or a click
     * always takes it: with only the tags in use on offer, a new tag met a "no results" list that swallowed the
     * first Enter. Reusing a tag is what makes it a filter, so the ones in use are one arrow key away.
     */
    public List<String> completeTag(final String query) {
        final String typed = normalizeTag(query);
        final Set<String> offered = new LinkedHashSet<>();
        if (!typed.isEmpty()) {
            offered.add(typed);
        }
        getTemplates().stream()
                .map(ContentTemplate::getTags)
                .flatMap(List::stream)
                .filter(tag -> tag.contains(typed))
                .distinct()
                .sorted(Comparator.comparing((String tag) -> !tag.startsWith(typed))
                        .thenComparing(Comparator.naturalOrder()))
                .forEach(offered::add);
        return List.copyOf(offered);
    }

    /**
     * How many CURRENT content items are built on each template, by template id; a template nothing uses has no
     * entry. With an organization scope (the manager page's {@code viewScope.orgScope}, or the organization
     * whose site the request is on) only that organization's pages count, which is also all its own editor may
     * learn; with none, every page counts and only site staff may ask.
     *
     * <p>One raw, uncached scan of the content table ({@code DAO.getAllContentRecords}, the same read the
     * referenced-delete check makes): an admin click, never a page render. The pages load it on a "Show usage"
     * click and keep the scalar map in the view; no aggregate is cached anywhere (docs/caching.md: the DAO
     * caches are the shared layer, and this table has no per-template index to invalidate).
     */
    public Map<String, Integer> usageCounts(final String orgId) {
        final String scope = Util.trimToNull(orgId);
        final Caller caller = callerSource.get();
        final boolean allowed = scope == null ? caller.has(PrivilegeCommands.CONTENT_ADMIN)
                : caller.has(PrivilegeCommands.CONTENT_ADMIN, scope);
        if (!allowed) {
            return new HashMap<>();
        }
        try {
            return countUsage(scope);
        } catch (final RuntimeException ex) {
            log.error("Unable to count template usage", ex);
            return new HashMap<>();
        }
    }

    /** {@link #usageCounts(String)} for the request's own site: an organization's site counts its own pages. */
    public Map<String, Integer> usageCounts() {
        return usageCounts(orgScope(null));
    }

    /**
     * How many current items on the request's own site use one template: the Add drawer's "How often is it
     * used?" for a designer. The same scan as {@link #usageCounts()}, so it too runs on a click, never a render.
     */
    public int usedBy(final String templateId) {
        return templateId == null ? 0 : usageCounts().getOrDefault(templateId, 0);
    }

    private static Map<String, Integer> countUsage(final String scope) {
        final Map<String, ContentInstance> current = new HashMap<>();
        DAO.getInstance().getAllContentRecords(Cached.NO).stream()
                .map(ContentRecord::getCurrent)
                .filter(Objects::nonNull)
                .forEach(instance -> current.put(instance.getId(), instance));
        final Map<String, Integer> counts = new HashMap<>();
        for (final ContentInstance instance : current.values()) {
            if (instance.getTemplateId() != null && onPageOf(scope, instance, current)) {
                counts.merge(instance.getTemplateId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Whether an item sits on a page of the scope's organization (any page when there is no scope). The
     * organization comes from the item's SECTION exactly as the edit gate derives it
     * ({@code ContentCommands.orgOf}): a container's child through its container's row.
     */
    private static boolean onPageOf(final String scope, final ContentInstance instance,
            final Map<String, ContentInstance> current) {
        if (scope == null) {
            return true;
        }
        final Organization.Id org = ContentCommands.orgOf(instance.getSection(), current.get(instance.getSection()));
        return org != null && scope.equals(org.getValue());
    }

    /**
     * The template a deep link into the manager opens in the editor, or null for none: {@code ?new=1} answers a
     * blank template (what the New Template button starts from), {@code ?edit=<id>} an editable copy of a stored
     * template the caller may author. An unknown id, or a row the caller may only read, opens nothing, the same
     * as that row's missing Edit button. The Add dialog's "New template" and "Edit this template" links use it.
     */
    public ContentTemplate deepLinkTemplate(final String newParam, final String editId) {
        if ("1".equals(newParam)) {
            return getTemplate("");
        }
        final String id = Util.trimToNull(editId);
        final ContentTemplate stored = id == null ? null : storedTemplate(id);
        return mayAuthor(stored) ? getTemplate(id) : null;
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
        return ContentCommands.retainCount(config);
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
