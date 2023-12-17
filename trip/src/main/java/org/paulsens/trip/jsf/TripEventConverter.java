package org.paulsens.trip.jsf;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.TripEvent;

@FacesConverter("tripEvent")
public class TripEventConverter implements Converter<TripEvent> {
    @Override
    public TripEvent getAsObject(FacesContext ctx, UIComponent comp, String value) {
        return DAO.getInstance().getTripEvent(value).join();
    }

    @Override
    public String getAsString(FacesContext ctx, UIComponent comp, TripEvent value) {
        return value.getId();
    }
}
