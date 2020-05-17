package org.paulsens.trip.jsf;

import javax.faces.context.ExternalContext;
import javax.faces.context.ExternalContextWrapper;
import javax.faces.context.FacesContext;
import javax.faces.context.FacesContextWrapper;

public class TripFacesContext extends FacesContextWrapper {
    private final FacesContext wrapped;
    private final TripExternalContext tripExternalContext;

    TripFacesContext(final FacesContext orig) {
        this.wrapped = orig;
        this.tripExternalContext = new TripExternalContext(orig.getExternalContext());
    }

    @Override
    public FacesContext getWrapped() {
        return wrapped;
    }

    @Override
    public ExternalContext getExternalContext() {
        return tripExternalContext;
    }

    public static class TripExternalContext extends ExternalContextWrapper {
        private final ExternalContext wrapped;

        public TripExternalContext(final ExternalContext ctx) {
            this.wrapped = ctx;
        }

        @Override
        public ExternalContext getWrapped() {
            return wrapped;
        }

        @Override
        public String getRequestServerName() {
            return "visitqueenofpeace.com";
        }
    }
}
