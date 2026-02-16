package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.FacesMessage.Severity;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import jakarta.inject.Named;
import java.lang.reflect.Array;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.model.SortMeta;
import org.primefaces.model.SortOrder;

@Slf4j
@ApplicationScoped
@Named("tripUtil")
@SuppressWarnings("unused")
public class TripUtilCommands {
    /**
     *  This method creates a {@code FacesMessage}.  It takes 3 String arguments: severity, summary, and detail.
     *
     *  @param  severity    "INFO", "WARN", "ERROR", or "FATAL".
     *  @param  summary     The message summary field.
     *  @param  detail      The message detail field.
     *
     *  @return A new instance of {@code jakarta.faces.application.FacesMessage}.
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
        addMessage(null, new FacesMessage(severity, summary, detail));
    }

    /**
     * Use with {@link #createFacesMessage(String, String, String)} to create / send a message that is tied to
     * a clientId.
     * @param clientId  The clientId related to this message.
     * @param msg       The {@code FacesMessage}.
     */
    public static void addMessage(final String clientId, final FacesMessage msg) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(clientId, msg);
        } else {
            log.warn("ClientId '" + clientId + "' had message: '" + msg.getSummary()
                    + "', level: " + msg.getSeverity());
        }
    }

    public SortMeta sortBy(final String sortBy) {
        return SortMeta.builder()
                .field(sortBy)
                .order(SortOrder.ASCENDING)
                .build();
    }

    public <T> List<T> asList(final Collection<T> collection) {
        return (collection instanceof List) ? ((List<T>) collection) : new ArrayList<>(collection);
    }

    /**
     * This returns a {@code List&lt;/U&gt;} by iterating over each item in the given {@code List&lt;T&gt;} and
     * applying the given mapping function {@code relativeEL}, which is an {@code EL} expressing relative to the item
     * in the list.
     *
     * @param collection    The source list.
     * @param relativeEL    The relative EL to apply to each item.
     * @return              A new unmodifiable list with the mapped values.
     * @param <T>   The original list type.
     * @param <U>   The new list type.
     */
    @SuppressWarnings("unchecked")
    public <T, U> List<U> mapList(final Collection<T> collection, final String relativeEL) {
        ELUtil util = ELUtil.getInstance();
        return collection.stream().map(item -> (U) util.eval(item, relativeEL)).toList();
    }

    public boolean isEmpty(final Object listOrArray) {
        if (listOrArray == null) {
            return true;
        }
        if (listOrArray instanceof Collection<?>) {
            return ((Collection<?>) listOrArray).isEmpty();
        }
        if (listOrArray.getClass().isArray()) {
            return ((Object[]) listOrArray).length == 0;
        }
        return false;
    }

    public DateTimeFormatter getDateTimeFormatter(final String pattern) {
        return DateTimeFormatter.ofPattern(pattern);
    }

    public String formatDateTime(final String pattern, final TemporalAccessor dateTime) {
        return getDateTimeFormatter(pattern).format(dateTime);
    }

    public LocalDateTime localDateTimeNow() {
        return LocalDateTime.now();
    }

    public ZonedDateTime withTimeZone(final LocalDateTime time, final String zoneId) {
        if (time == null) {
            return null;
        }
        final ZonedDateTime atUTC = time.atZone(ZoneId.of("UTC"));
        return zoneId == null ? atUTC : atUTC.withZoneSameInstant(ZoneId.of(zoneId));
    }

    public LocalDate localDateNow() {
        return LocalDate.now();
    }

    public LocalDateTime epochSecondsToUTCLocalDateTime(final Long epochSeconds) {
        return epochSeconds == null ? null : LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC);
    }

    public <T> List<T> getMapValues(Map<?, T> map) {
        return asList(map.values());
    }

    public static <T> List<T> arrayToList(final T[] arr) {
        if (arr == null || arr.length == 0) {
            return List.of();
        }
        return Arrays.asList(arr);
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

    public Object evalEL(final String str) {
        if (str == null) {
            return null;
        }
        return ELUtil.getInstance().eval(str);
    }

    public void throwException(final String msg) {
        throw new RuntimeException(msg);
    }
}
