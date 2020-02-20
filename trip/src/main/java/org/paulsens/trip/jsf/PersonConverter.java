package org.paulsens.trip.jsf;

import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;
import javax.faces.convert.FacesConverter;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;

@FacesConverter("person")
public class PersonConverter implements Converter {
    @Override
    public Object getAsObject(FacesContext ctx, UIComponent comp, String value) {
        return DynamoUtils.getInstance().getPerson(value);
    }

    @Override
    public String getAsString(FacesContext ctx, UIComponent comp, Object value) {
        if (value instanceof Person) {
            return ((Person) value).getId();
        } else if (value instanceof String) {
            return (String) value;
        }
        return null;
    }
}
