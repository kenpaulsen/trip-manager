package org.paulsens.trip.action;

import java.lang.reflect.Array;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.faces.application.FacesMessage.Severity;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.inject.Named;

@ApplicationScoped
@Named("tripUtil")
public class TripUtilCommands {
    /**
     *  This method creates a {@code FacesMessage}.  It takes 3 String arguments: severity, summary, and detail.
     *
     *  @param  severity    "INFO", "WARN", "ERROR", or "FATAL".
     *  @param  summary     The message summary field.
     *  @param  detail      The message detail field.
     *
     *  @return A new instance of {@code javax.faces.application.FacesMessage}.
     */
    public FacesMessage createFacesMessage(final String severity, final String summary, final String detail) {
        final Severity sevObj;
        if ("WARN".equals(severity)) {
            sevObj = FacesMessage.SEVERITY_WARN;
        } else if ("ERROR".equals(severity)) {
            sevObj = FacesMessage.SEVERITY_ERROR;
        } else if ("FATAL".equals(severity)) {
            sevObj = FacesMessage.SEVERITY_FATAL;
        } else  {
            sevObj = FacesMessage.SEVERITY_INFO;
        }
        return new FacesMessage(sevObj, summary, detail);
    }

    public void infoMsg(final String summary, final String detail) {
        addFacesMessage(FacesMessage.SEVERITY_INFO, summary, detail);
    }
    public void warnMsg(final String summary, final String detail) {
        addFacesMessage(FacesMessage.SEVERITY_WARN, summary, detail);
    }
    public void errorMsg(final String summary, final String detail) {
        addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail);
    }
    public void fatalMsg(final String summary, final String detail) {
        addFacesMessage(FacesMessage.SEVERITY_FATAL, summary, detail);
    }
    static void addFacesMessage(final Severity severity, final String summary, final String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public <T> List<T> asList(final Collection<T> collection) {
        return (collection instanceof List) ? ((List<T>) collection) : new ArrayList<>(collection);
    }

    public LocalDateTime localDateTimeNow() {
        return LocalDateTime.now();
    }

    public LocalDate localDateNow() {
        return LocalDate.now();
    }

    public <T> List<T> getMapValues(Map<?, T> map) {
        return asList(map.values());
    }

    public SelectItem[] getSelectItems(final Collection<String> labels, final Collection<Object> values) {
        if ((labels == null) || (values == null)) {
            return new SelectItem[0];
        }
        int len = labels.size();
        if (len != values.size()) {
            throw new IllegalArgumentException(
                    "'labels' and 'values' size must be equal length!");
        }
        SelectItem[] options = (SelectItem []) Array.newInstance(SelectItem.class, len);

        // Iterate through and create the SelectItems
        final Iterator<String> labelIT = labels.iterator();
        final Iterator<Object> valueIT = values.iterator();
        for (int idx=0; idx < len; idx++) {
            options[idx] = new SelectItem(valueIT.next(), labelIT.next());
        }

        // Return the result
        return options;
    }

    public void throwException(final String msg) {
        throw new RuntimeException(msg);
    }
}