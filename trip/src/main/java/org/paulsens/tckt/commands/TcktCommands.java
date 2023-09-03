package org.paulsens.tckt.commands;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.File;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.tckt.dao.FilesystemPersistence;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.BindingType;
import org.paulsens.tckt.model.Bindings;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Id;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;

@Slf4j
@Named("tckt")
@ApplicationScoped
public class TcktCommands {
    private static final String ANSWERS_PATH = "/tckt/answers";
    private static final String COURSES_PATH = "/tckt/courses";
    private static final String QUESTIONS_PATH = "/tckt/questions";
    private static final String TICKETS_PATH = "/tckt/tickets";
    private static final String USERS_PATH = "/tckt/users";
    private static final String BINDING_SUFFIX = ".bindings";
    private static final String QUESTIONS_BINDING_PATH = BINDING_SUFFIX + "/qb";
    // FIXME: We can have many FilesystemPersistence instances with different basePaths... for now 1
    private final String BASE_PATH = "/tmp"; // FIXME: decide what this should be
    // FIXME: persistence can be stored in session (or something similar) to make multi-tenant
    private final FilesystemPersistence persistence = new FilesystemPersistence(BASE_PATH);

    public TcktCommands() {
        persistence.setDeafultAnswerPath(ANSWERS_PATH);
        persistence.setDeafultAnswerPath(COURSES_PATH);
        persistence.setDeafultAnswerPath(QUESTIONS_PATH);
        persistence.setDeafultAnswerPath(TICKETS_PATH);
        persistence.setDeafultAnswerPath(USERS_PATH);
        ensureDirectories(BASE_PATH + ANSWERS_PATH);
        ensureDirectories(BASE_PATH + COURSES_PATH);
        ensureDirectories(BASE_PATH + QUESTIONS_PATH);
        ensureDirectories(BASE_PATH + TICKETS_PATH);
        ensureDirectories(BASE_PATH + USERS_PATH);
        ensureDirectories(BASE_PATH + QUESTIONS_PATH + QUESTIONS_BINDING_PATH);
    }

    public void bindUser(final User.Id userId, final Id destId, final BindingType destType) {
        bind(USERS_PATH + BINDING_SUFFIX, userId.getValue(), destId.getValue(), BindingType.USER, destType);
    }
    public void bindCourse(final Course.Id courseId, final Id destId, final BindingType destType) {
        bind(COURSES_PATH + BINDING_SUFFIX, courseId.getValue(), destId.getValue(), BindingType.COURSE, destType);
    }
    public void bindTicket(final Ticket.Id ticketId, final Id destId, final BindingType destType) {
        bind(TICKETS_PATH + BINDING_SUFFIX, ticketId.getValue(), destId.getValue(), BindingType.TICKET, destType);
    }
    public void bindQuestion(final Question.Id questionId, final Id destId, final BindingType destType) {
        final String qid = questionId.getValue();
        bind(getQuestionBindingPath(QUESTIONS_PATH, qid), qid, destId.getValue(), BindingType.QUESTION, destType);
    }

    void ensureDirectories(final String path) {
        final String[] paths = path.split("/");
        final StringBuilder pathBuilder = new StringBuilder("/");
        // Iterate through all the paths, except the last one (which is expected to be a filename)
        for (int idx = 0; idx < paths.length - 1; idx++) {
            pathBuilder.append(paths[idx]);
            final File pathToCheck = new File(pathBuilder.toString());
            if (!pathToCheck.exists()) {
                // Create it!
                if (!pathToCheck.mkdir()) {
                    throw new IllegalStateException("Unable to create directory for some reason: " + pathBuilder);
                }
            } else if (!pathToCheck.isDirectory()) {
                throw new IllegalStateException("Unable to create directory b/c file exists already: " + pathBuilder);
            }
            pathBuilder.append("/");
        }
    }

    // NOTE: bindAnswer(...) // << NO!! Answers have direct references, they don't need bindings!!
    private void bind(final String relPath,
            final String srcId, final String destId, final BindingType srcType, final BindingType destType) {
        final Bindings bindings = persistence.getBindings(relPath);
        final Binding newBinding = new Binding(null, srcId, destId, srcType, destType);
        if (bindings.containsValue(newBinding)) {
            log.warn("Binding already present: " + newBinding);
            return;
        }
        persistence.cacheBinding(relPath, newBinding);
        persistence.saveBindings(relPath);
    }

    /**
     * This method computes the directory and file that should store the bindings for the given question. Since
     * questions have many answers, and there are many questions, we will store the bindings on a per-question basis.
     * However, this implementation will use a hash of the question id, so there's a very, very small chance of the
     * same hash. Therefor, it should not be assumed that all bindings in a question binding file refer to the same
     * questionId.
     * @param qRelPath      The relative file path to the question file (not question bindings).
     * @param questionId    The questionId for which to compute the bindings file relative path.
     * @return The relative path to the bindings file for the given questionId.
     */
    private String getQuestionBindingPath(final String qRelPath, final String questionId) {
        final int hash = questionId.hashCode();
        // /tckt/questions.bindings/qb + hash
        return qRelPath + QUESTIONS_BINDING_PATH + hash;
    }

    // Login
    public User login(final String login, final String pw) {
        if (login == null || pw == null) {
            // FIXME: logging / audit
            return null;
        }
        final User user = persistence.getUser(login, pw).orElse(null);
        // FIXME: Audit?
        log.info("TCKT: Login " + (user == null ? "FAILED" : "SUCCEEDED") + " for user " + login);
        return user;
    }
    public User getUser(final User.Id id) {
        return persistence.getUser(id);
    }

    // Getters
    public List<User> getUsers() {
        final List<User> result = new ArrayList<>(persistence.getUsers(USERS_PATH).values());
        result.sort(Comparator.comparing(User::getLast));
        return result;
    }
    public List<Course> getCourses() {
        final List<Course> result = new ArrayList<>(persistence.getCourses(COURSES_PATH).values());
        result.sort(Comparator.comparing(Course::getName));
        return result;
    }
    public Course getCourse(final Course.Id cid) {
        return persistence.getCourse(cid);
    }
    public List<Course> getCoursesForTeacher(final User.Id userId) {
        // FIXME: Use bindings instead??
        return persistence.getCourses(COURSES_PATH).values().stream()
                .filter(course -> course.getTeacherId().equals(userId))
                .sorted(Comparator.comparing(Course::getName))
                .toList();
    }
    public List<User> getStudentsForCourse(final Course.Id id) {
        return persistence.getBindings(COURSES_PATH + BINDING_SUFFIX)
                .resolve(persistence, List.of(
                        binding -> binding.getSrcId().equals(id),
                        binding -> binding.getDestId().getType().equals(BindingType.USER)))
                .map(user -> (User) user)
                .filter(user -> user.isType(User.Type.STUDENT))
                .toList();
    }
    public List<Ticket> getTickets() {
        final List<Ticket> result = new ArrayList<>(persistence.getTickets(TICKETS_PATH).values());
        result.sort(Comparator.comparing(Ticket::getDate));
        return result;
    }
    public List<Ticket> getTicketsForCourse(final Course.Id id) {
        return persistence.getBindings(COURSES_PATH + BINDING_SUFFIX)
                .resolve(persistence, List.of(
                        binding -> binding.getSrcId().equals(id),
                        binding -> binding.getDestId().getType().equals(BindingType.TICKET)))
                .map(ticket -> (Ticket) ticket)
                .toList();
    }
    public List<Question> getQuestionsForTicket(final Ticket.Id id) {
        return persistence.getBindings(TICKETS_PATH + BINDING_SUFFIX)
                .resolve(persistence, List.of(
                        binding -> binding.getSrcId().equals(id),
                        binding -> binding.getDestId().getType().equals(BindingType.QUESTION)))
                .map(question -> (Question) question)
                .toList();
    }
    public List<Question> getQuestions() {
        final List<Question> result = new ArrayList<>(persistence.getQuestions(QUESTIONS_PATH).values());
        // FIXME: move default sorting to Persistence layer so it is cached
        result.sort(Comparator.comparing(Question::getCreatedDate));
        return result;
    }

    // Create
    public User createStudent(final String first, final String last) {
        return new User(first, last, User.Type.STUDENT);
    }
    public User createAdmin(final String first, final String last) {
        return new User(first, last, User.Type.ADMIN);
    }
    public Course createCourse(final String name, final User.Id teacherId) {
        return createCourse(name, teacherId, Year.now());
    }
    public Course createCourse(final String name, final User.Id teacherId, final Year year) {
        return new Course(name, teacherId, year);
    }

    // Create Ticket
    public Ticket createTicket(final String title, final LocalDate date, final User.Id owner) {
        return new Ticket(owner, date, title);
    }

    // Store... (which does not currently write to disk)
    public void storeUser(final User user) {
        persistence.cacheUser(user);
    }
    public void storeCourse(final Course course) {
        persistence.cacheCourse(course);
    }
    public void storeTicket(final Ticket ticket) {
        persistence.cacheTicket(ticket);
    }

    // Persist... (which **DOES** write to disk)
    public void writeUsers() {
        persistence.saveUsers();
    }
    public void writeCourses() {
        persistence.saveCourses();
    }
    public void writeTickets() {
        persistence.saveTickets();
    }

    // ID Methods
    public User.Id userId(final String id) {
        return new User.Id(id);
    }
    public Course.Id courseId(final String id) {
        return new Course.Id(id);
    }
    public Answer.Id answerId(final String id) {
        return new Answer.Id(id);
    }
    public Ticket.Id ticketId(final String id) {
        return new Ticket.Id(id);
    }
    public Question.Id questionId(final String id) {
        return new Question.Id(id);
    }
}
