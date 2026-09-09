package org.paulsens.trip.jsf;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

/**
 * Registers {@link TripAjaxExceptionHandler}. Wired in {@code medjugorje/webapp/WEB-INF/faces-config.xml}
 * (the LIVE descriptor -- the one in {@code trip/} is only the build scaffold).
 *
 * <p>Unlike the {@code TripFacesContextFactory} that used to live in that file, this extension point is
 * genuinely consulted by Mojarra 4.1: {@code ExceptionHandlerFactory} is created per request and every
 * lifecycle phase routes unhandled exceptions through the handler it returns. {@code TripAjaxExceptionHandlerIT}
 * exercises it through a real postback so a silent regression cannot repeat that history.
 */
public class TripAjaxExceptionHandlerFactory extends ExceptionHandlerFactory {

    public TripAjaxExceptionHandlerFactory(final ExceptionHandlerFactory wrapped) {
        super(wrapped);
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new TripAjaxExceptionHandler(getWrapped().getExceptionHandler());
    }
}
