package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;

@Slf4j
@FacesConverter("person")
public class PersonConverter implements Converter {
    @Override
    public Object getAsObject(FacesContext ctx, UIComponent comp, String value) {
        final Person person = DAO.getInstance().getPerson(Person.Id.from(value))
                .orTimeout(3_000, TimeUnit.MILLISECONDS)
                .join()
                .orElse(null);
        if (person == null) {
            log.warn("Unable to find person: {}", value);
        }
        return person;
    }

    @Override
    public String getAsString(FacesContext ctx, UIComponent comp, Object value) {
        if (value instanceof Person) {
            return ((Person) value).getId().getValue();
        } else if (value instanceof Person.Id) {
            return ((Person.Id) value).getValue();
        } else if (value instanceof String) {
            return (String) value;
        }
        log.warn("Unable to convert {} of type {} to a Person.", value, value == null ? "null" : value.getClass().getName());
        return null;
    }
}
