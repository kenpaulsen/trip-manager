package org.paulsens.trip.jsf;

import jakarta.faces.application.ViewHandler;
import jakarta.faces.application.ViewHandlerWrapper;
import jakarta.faces.context.FacesContext;
import java.util.Locale;
import org.paulsens.trip.audit.RequestContext;

/**
 * Gives every view the language its request was resolved to ({@code docs/i18n.md}).
 *
 * <p>Faces would otherwise match the browser's {@code Accept-Language} against {@code locale-config} by itself,
 * which knows nothing of the visitor's explicit choice, the signed-in person's preference or the languages
 * this SITE offers. {@code SessionRecoveryFilter} has already weighed all of those into
 * {@code RequestContext.locale}; answering it here is what makes converters, PrimeFaces' client locale and
 * {@code #{view.locale}} agree with the words on the page, with no {@code f:view locale} in any template.
 */
public class TripViewHandler extends ViewHandlerWrapper {

    public TripViewHandler(final ViewHandler wrapped) {
        super(wrapped);
    }

    @Override
    public Locale calculateLocale(final FacesContext context) {
        return RequestContext.currentLocale();
    }
}
