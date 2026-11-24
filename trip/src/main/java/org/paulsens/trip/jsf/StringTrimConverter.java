package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import org.paulsens.trip.util.Util;

@FacesConverter(forClass = String.class)
public class StringTrimConverter implements Converter<String> {
    @Override
    public String getAsObject(final FacesContext ctx, final UIComponent comp, final String value) {
        return Util.trimToNull(value);
    }

    @Override
    public String getAsString(final FacesContext ctx, final UIComponent comp, final String value) {
        return value;
    }
}
