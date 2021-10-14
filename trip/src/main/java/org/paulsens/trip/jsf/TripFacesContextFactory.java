package org.paulsens.trip.jsf;

import jakarta.faces.FacesException;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextFactory;
import jakarta.faces.lifecycle.Lifecycle;

public class TripFacesContextFactory extends FacesContextFactory {
    private final FacesContextFactory wrapped;

    public TripFacesContextFactory(final FacesContextFactory orig) {
        this.wrapped = orig;
    }

    @Override
    public FacesContext getFacesContext(
            final Object context, final Object request, final Object response, final Lifecycle lifecycle)
            throws FacesException {
        return new TripFacesContext(wrapped.getFacesContext(context, request, response, lifecycle));
    }
}
