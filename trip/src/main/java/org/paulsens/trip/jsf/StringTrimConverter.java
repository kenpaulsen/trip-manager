package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;

@FacesConverter(forClass = String.class)
public class StringTrimConverter implements Converter<String> {
    @Override
    public String getAsObject(final FacesContext ctx, final UIComponent comp, final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String getAsString(final FacesContext ctx, final UIComponent comp, final String value) {
        return value;
    }
}
