package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import org.paulsens.trip.model.Person;

@FacesConverter("personId")
public class PersonIdConverter implements Converter {
    @Override
    public Object getAsObject(FacesContext ctx, UIComponent comp, String value) {
        return Person.Id.from(value);
    }

    @Override
    public String getAsString(FacesContext ctx, UIComponent comp, Object value) {
        if (value instanceof Person.Id) {
            return ((Person.Id) value).getValue();
        } if (value instanceof Person) {
            return ((Person) value).getId().getValue();
        } else if (value instanceof String) {
            return (String) value;
        }
        return null;
    }
}
