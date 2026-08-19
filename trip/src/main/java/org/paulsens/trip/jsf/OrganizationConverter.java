package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;

/** Organization &lt;-&gt; id, for the org autocomplete pickers (the {@link PersonConverter} pattern). */
@Slf4j
@FacesConverter("organization")
public class OrganizationConverter implements Converter {
    @Override
    public Object getAsObject(final FacesContext ctx, final UIComponent comp, final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final Organization org = DAO.getInstance()
                .getOrganization(Organization.Id.from(value.trim()), Cached.YES).orElse(null);
        if (org == null) {
            log.warn("Unable to find organization: {}", value);
        }
        return org;
    }

    @Override
    public String getAsString(final FacesContext ctx, final UIComponent comp, final Object value) {
        if (value instanceof Organization org) {
            return org.getId().getValue();
        } else if (value instanceof Organization.Id id) {
            return id.getValue();
        } else if (value instanceof String str) {
            return str;
        }
        if (value != null) {
            log.warn("Unable to convert {} of type {} to an Organization.", value, value.getClass().getName());
        }
        return null;
    }
}
