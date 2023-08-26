package org.paulsens.tckt.dao;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.Bindings;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.tckt.model.Id;

/**
 * This class will handle caching, retrieval, and persistence of data.
 */
@Slf4j
public class FilesystemPersistence {
    // FIXME: Think about concurrent (or writes w/i a few seconds of each other) and efficiency / performance
    private final String basePath;
    private final Map<String, Map<Answer.Id, Answer>> answerCache;
    private final Map<String, Map<Course.Id, Course>> courseCache;
    private final Map<String, Map<Question.Id, Question>> questionCache;
    private final Map<String, Map<Ticket.Id, Ticket>> ticketCache;
    private final Map<String, Map<User.Id, User>> userCache;
    private Map<String, Bindings> bindingCache;
    @Getter @Setter
    private String deafultAnswerPath = "/tckt/answers";
    @Getter @Setter
    private String defaultCoursePath = "/tckt/courses";
    @Getter @Setter
    private String defaultQuestionPath = "/tckt/questions";
    @Getter @Setter
    private String defaultTicketPath = "/tckt/tickets";
    @Getter @Setter
    private String defaultUserPath = "/tckt/users";
    //private String BINDING_SUFFIX = ".bindings";

    public FilesystemPersistence(final String basePath) {
        this.basePath = basePath;
        this.answerCache = new ConcurrentHashMap<>();
        this.courseCache = new ConcurrentHashMap<>();
        this.questionCache = new ConcurrentHashMap<>();
        this.ticketCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        this.bindingCache = new ConcurrentHashMap<>();
    }

    // Answers
    public synchronized void cacheAnswer(final String relPath, final Answer answer) {
        cache(relPath == null ? deafultAnswerPath : relPath, answerCache, answer.getId(), answer);
    }
    public synchronized void saveAnswers(final String relPath) {
        save(relPath == null ? deafultAnswerPath : relPath, answerCache, (fn, answers) -> FilesystemTcktDAO.getInstance().saveAnswers(fn, answers));
    }
    public synchronized Map<Answer.Id, Answer> getAnswers(final String relPath) {
        return getOrLoad(relPath == null ? deafultAnswerPath : relPath, answerCache, fn -> FilesystemTcktDAO.getInstance().loadAnswers(fn));
    }
    public synchronized Answer getAnswer(final String relPath, final Answer.Id id) {
        return getAnswers(relPath).get(id);
    }

    // Bindings
    public synchronized void cacheBinding(final String relPath, final Binding binding) {
        final Bindings bindings = bindingCache.computeIfAbsent(getFilename(relPath),
                fn -> new Bindings(new ConcurrentHashMap<>()));
        bindings.put(binding.getId(), binding);
    }
    public synchronized void saveBindings(final String relPath) {
        save(relPath, unwrappedCopy(), (fn, bindings) -> FilesystemTcktDAO.getInstance().saveBindings(fn, bindings));
    }
    public synchronized Bindings getBindings(final String relPath) {
        final String filename = getFilename(relPath);
        return bindingCache.computeIfAbsent(filename,
                fn -> new Bindings(FilesystemTcktDAO.getInstance().loadBindings(filename)));
    }
    public synchronized Binding getBinding(final String relPath, final Binding.Id id) {
        return getBindings(relPath).get(id);
    }

    // Courses
    public void cacheCourse(final Course course) {
        cacheCourse(null, course);
    }
    public synchronized void cacheCourse(final String relPath, final Course course) {
        cache(relPath == null ? defaultCoursePath : relPath, courseCache, course.getId(), course);
    }
    public void saveCourses() {
        saveCourses(null);
    }
    public synchronized void saveCourses(final String relPath) {
        save(relPath == null ? defaultCoursePath : relPath, courseCache, (fn, courses) -> FilesystemTcktDAO.getInstance().saveCourses(fn, courses));
    }
    public Map<Course.Id, Course> getCourses() {
        return getCourses(null);
    }
    public synchronized Map<Course.Id, Course> getCourses(final String relPath) {
        return getOrLoad(relPath == null ? defaultCoursePath : relPath, courseCache, fn -> FilesystemTcktDAO.getInstance().loadCourses(fn));
    }
    public Course getCourse(final Course.Id id) {
        return getCourse(null, id);
    }
    public synchronized Course getCourse(final String relPath, final Course.Id id) {
        return getCourses(relPath).get(id);
    }

    // Questions
    public synchronized void cacheQuestion(final String relPath, final Question question) {
        cache(relPath == null ? defaultQuestionPath : relPath, questionCache, question.getId(), question);
    }
    public synchronized void saveQuestions(final String relPath) {
        save(relPath == null ? defaultQuestionPath : relPath, questionCache, (fn, questions) -> FilesystemTcktDAO.getInstance().saveQuestions(fn, questions));
    }
    public synchronized Map<Question.Id, Question> getQuestions(final String relPath) {
        return getOrLoad(relPath == null ? defaultQuestionPath : relPath, questionCache, fn -> FilesystemTcktDAO.getInstance().loadQuestions(fn));
    }
    public synchronized Question getQuestion(final String relPath, final Question.Id id) {
        return getQuestions(relPath).get(id);
    }

    // Tickets
    public void cacheTicket(final Ticket ticket) {
        cacheTicket(null, ticket);
    }
    public synchronized void cacheTicket(final String relPath, final Ticket ticket) {
        cache(relPath == null ? defaultTicketPath : relPath, ticketCache, ticket.getId(), ticket);
    }
    public void saveTickets() {
        saveTickets(null);
    }
    public synchronized void saveTickets(final String relPath) {
        save(relPath == null ? defaultTicketPath : relPath, ticketCache, (fn, tickets) -> FilesystemTcktDAO.getInstance().saveTickets(fn, tickets));
    }
    public Map<Ticket.Id, Ticket> getTickets() {
        return getTickets(null);
    }
    public synchronized Map<Ticket.Id, Ticket> getTickets(final String relPath) {
        return getOrLoad(relPath == null ? defaultTicketPath : relPath, ticketCache, fn -> FilesystemTcktDAO.getInstance().loadTickets(fn));
    }
    public Ticket getTicket(final Ticket.Id id) {
        return getTicket(null, id);
    }
    public synchronized Ticket getTicket(final String relPath, final Ticket.Id id) {
        return getTickets(relPath).get(id);
    }

    // Users
    public void cacheUser(final User user) {
        cacheUser(null, user);
    }
    public synchronized void cacheUser(final String relPath, final User user) {
        cache(relPath == null ? defaultUserPath : relPath, userCache, user.getId(), user);
    }
    public void saveUsers() {
        saveUsers(null);
    }
    public synchronized void saveUsers(final String relPath) {
        save(relPath == null ? defaultUserPath : relPath, userCache, (fn, users) -> FilesystemTcktDAO.getInstance().saveUsers(fn, users));
    }
    public Map<User.Id, User> getUsers() {
        return getUsers(null);
    }
    public synchronized Map<User.Id, User> getUsers(final String relPath) {
        return getOrLoad(relPath == null ? defaultUserPath : relPath, userCache, fn -> FilesystemTcktDAO.getInstance().loadUsers(fn));
    }
    public User getUser(final User.Id id) {
        return getUser(null, id);
    }
    public synchronized User getUser(final String relPath, final User.Id id) {
        return getUsers(relPath).get(id);
    }

    private <ID extends Id, V> Map<ID, V> getOrLoad(
            final String relPath, final Map<String, Map<ID, V>> cache, final Function<String, Map<ID, V>> loadFunc) {
        final String filename = getFilename(relPath);
        return cache.computeIfAbsent(filename, fn -> loadFunc.apply(filename));
    }

    private <ID extends Id, V> void save(
            final String relPath,
            final Map<String, Map<ID, V>> cache,
            final SaveFunction<V> saveFunc) {
        final String filename = getFilename(relPath);
        final Map<ID, V> result = cache.get(filename);
        if (result != null) {
            try {
                saveFunc.apply(filename, result.values());
            } catch (final IOException ex) {
                log.error("Error while saving '" + filename + "'!", ex);
            }
        }
    }

    private <ID extends Id, V> void cache(final String relPath, final Map<String, Map<ID, V>> cache, ID id, V value) {
        final Map<ID, V> map = cache.computeIfAbsent(getFilename(relPath), fn -> new ConcurrentHashMap<>());
        map.put(id, value);
    }

    private String getFilename(final String relPath) {
        if (relPath.startsWith("/")) {
            return basePath + relPath;
        } else {
            throw new IllegalArgumentException("All paths must start w/ a '/' relative to the base directory!");
        }
    }

    private Map<String, Map<Binding.Id, Binding>> unwrappedCopy() {
        final Map<String, Map<Binding.Id, Binding>> map = new ConcurrentHashMap<>();
        bindingCache.forEach((key, value) -> map.put(key, value.getWrapped()));
        return map;
    }

    @FunctionalInterface
    private interface SaveFunction<T> {
        void apply(final String fn, final Collection<T> v) throws IOException;
    }
}
