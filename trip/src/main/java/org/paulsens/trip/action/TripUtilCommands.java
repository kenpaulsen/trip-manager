package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.FacesMessage.Severity;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Array;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.util.ScopeUtil;
import org.paulsens.trip.web.Sessions;
import org.primefaces.model.SortMeta;
import org.primefaces.model.SortOrder;

@Slf4j
@ApplicationScoped
@Named("tripUtil")
@SuppressWarnings("unused")
public class TripUtilCommands {
    /**
     * Whether this JVM is running on fake data rather than against AWS, for EL that must not fire in a
     * non-production deployment -- {@code #{tripUtil.local}}.
     *
     * <p>The motivating case is the analytics tag in {@code template.xhtml}: every local run, every
     * {@code TRIP_LOCAL_MODE=true} container and every webtest page load was reporting hits to the real Google
     * Analytics property, so the production numbers included test traffic. It also made the test suite wait on an
     * internet round trip for a script it had no interest in.
     *
     * @return {@code true} when {@link LocalMode} resolved this JVM to local mode.
     */
    public boolean isLocal() {
        return LocalMode.isLocal();
    }

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

    /** Path + query of the current request (no scheme/host), the format {@code afterLoginURL} is stashed in. */
    public String currentPathAndQuery() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null || !(ctx.getExternalContext().getRequest() instanceof HttpServletRequest req)) {
            return "/";
        }
        final String query = req.getQueryString();
        return req.getRequestURI() + ((query == null) ? "" : "?" + query);
    }

    /**
     * The login URL for an auth redirect, carrying the return target as {@code ?to=} rather than trusting the
     * session alone: the stash-only flow lost the deep link whenever the session died between the gated page
     * and the end of login (a redeploy, or a user parked on the login screen past the session timeout) -- they
     * signed in fine and landed on the home page instead of where they were going.
     */
    public String loginUrl() {
        return "/account/login.jsf?to=" + URLEncoder.encode(currentPathAndQuery(), StandardCharsets.UTF_8);
    }

    /**
     * {@code afterLoginURL} exposed as a form value so the STATELESS login pages carry it through their own
     * POSTs (an {@code h:inputHidden} re-stashes it into whatever session the POST lands on). The setter is
     * fed by a user-controlled field, so it accepts only same-site absolute paths -- anything else (foreign
     * hosts, protocol-relative {@code //}, backslash tricks) is dropped rather than becoming an open redirect.
     * An empty submitted value is a no-op: it must not wipe a stash some other page just set.
     */
    public String getReturnPath() {
        final Object stash = ScopeUtil.getInstance().getSessionMap(Sessions.AFTER_LOGIN_URL);
        return (stash == null) ? null : normalizeReturnPath(stash.toString());
    }

    public void setReturnPath(final String value) {
        final String clean = normalizeReturnPath(value);
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (clean != null && ctx != null) {
            ctx.getExternalContext().getSessionMap().put(Sessions.AFTER_LOGIN_URL, clean);
        }
    }

    /**
     * Where the browser says it came FROM, as a same-site path -- or null when that is unknown, foreign, or
     * useless as a return target (a login page, or this very page). Lets a form's Save send people back to
     * whatever page linked here instead of a hardcoded landing page. Null means "use your fallback": the
     * Referer header is optional and privacy settings routinely strip it, so callers must always have one.
     */
    public String refererPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null || !(ctx.getExternalContext().getRequest() instanceof HttpServletRequest req)) {
            return null;
        }
        final String referer = normalizeReturnPath(req.getHeader("Referer"));
        if (referer == null
                || referer.startsWith("/account/login")
                || referer.startsWith("/account/createAccount")
                || referer.startsWith(req.getRequestURI())) {
            return null;
        }
        return referer;
    }

    /** {@code path} with an {@code info=} banner message appended (encoded), joining ? or &amp; correctly. */
    public String withInfo(final String path, final String message) {
        return path + (path.contains("?") ? "&" : "?") + "info="
                + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    /**
     * Same-site path+query from a stash or form value, or null when it cannot be trusted. Absolute URLs (the
     * legacy stash format every gated page writes) are reduced to their path; everything else must already be
     * an absolute path.
     */
    static String normalizeReturnPath(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            final int hostStart = path.indexOf("//") + 2;
            final int firstSlash = path.indexOf('/', hostStart);
            path = (firstSlash < 0) ? "/" : path.substring(firstSlash);
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            return null;
        }
        return path;
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

    /**
     * The title a flight trip-event carries, e.g. {@code "PDX -> FCO"}.
     *
     * <p>Airport codes are uppercased and trimmed so the title does not depend on how the manager typed them.
     *
     * @param startIATA departure airport code
     * @param endIATA   arrival airport code
     * @return the composed title
     */
    public String composeFlightTitle(final String startIATA, final String endIATA) {
        return blankToEmpty(startIATA).trim().toUpperCase() + " -> " + blankToEmpty(endIATA).trim().toUpperCase();
    }

    /**
     * The notes body for a flight trip-event, e.g.
     * {@code "Alaska Airlines AS 123: 8:15am -> 11:45am (9h 10m)"}, with a {@code +1} marker when the flight
     * lands on a later calendar day.
     *
     * <p>This used to be a JSFT script inline in {@code trip/edit.xhtml}, where nothing could unit-test it and a
     * null field would abort the whole block. Two bugs came out of the move: the departure time was formatted
     * with {@code K} (hour 0-11), so a 12:15pm departure read {@code 0:15pm}; and the overnight marker compared
     * {@code dayOfYear}, which goes negative across New Year, so a 31-Dec flight lost its {@code +1}.
     *
     * @param flightNumber airline and flight number, free text
     * @param start        departure date and time
     * @param end          arrival date and time
     * @param duration     duration as the manager typed it, e.g. {@code "9h 10m"}
     * @return the composed notes
     */
    public String composeFlightNotes(final String flightNumber, final LocalDateTime start, final LocalDateTime end,
            final String duration) {
        final String overnight = (start != null && end != null && end.toLocalDate().isAfter(start.toLocalDate()))
                ? " +1" : "";
        return blankToEmpty(flightNumber).trim() + ": " + flightClock(start) + " -> " + flightClock(end) + overnight
                + " (" + blankToEmpty(duration).trim() + ")";
    }

    /** Wall-clock half of a flight time, lowercased: {@code 8:15am}. */
    private String flightClock(final LocalDateTime when) {
        return when == null ? "" : formatDateTime("h:mma", when).toLowerCase(Locale.ROOT);
    }

    private static String blankToEmpty(final String value) {
        return value == null ? "" : value;
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
