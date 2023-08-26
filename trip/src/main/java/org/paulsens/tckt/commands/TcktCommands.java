package org.paulsens.tckt.commands;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.tckt.dao.FilesystemPersistence;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.BindingType;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.trip.action.TripUtilCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;

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
    // FIXME: We can have many FilesystemPersistence instances with different basePaths... for now 1
    private final String BASE_PATH = "/tmp"; // FIXME: decide what this should be
    private final FilesystemPersistence persistence = new FilesystemPersistence(BASE_PATH);

    public TcktCommands() {
        persistence.setDeafultAnswerPath(ANSWERS_PATH);
        persistence.setDeafultAnswerPath(COURSES_PATH);
        persistence.setDeafultAnswerPath(QUESTIONS_PATH);
        persistence.setDeafultAnswerPath(TICKETS_PATH);
        persistence.setDeafultAnswerPath(USERS_PATH);
    }

    // Getters
    public List<User> getUsers() {
        final List<User> result = new ArrayList<>(persistence.getUsers(USERS_PATH).values());
        result.sort(Comparator.comparing(User::getName));
        return result;
    }
    public List<Course> getCourses() {
        final List<Course> result = new ArrayList<>(persistence.getCourses(COURSES_PATH).values());
        result.sort(Comparator.comparing(Course::getName));
        return result;
    }
    public List<Course> getCoursesForTeacher(final User.Id userId) {
        // FIXME: Use bindings instead??
        return persistence.getCourses(COURSES_PATH).values().stream()
                .filter(course -> course.getTeacherId().equals(userId))
                .sorted(Comparator.comparing(Course::getName))
                .toList();
    }
    public List<Ticket> getTickets() {
        final List<Ticket> result = new ArrayList<>(persistence.getTickets(TICKETS_PATH).values());
        result.sort(Comparator.comparing(Ticket::getDate));
        return result;
    }
    public List<Question> getQuestionsForTicket(final Ticket.Id id) {
        return persistence.getBindings(TICKETS_PATH + BINDING_SUFFIX)
                .resolve(persistence, List.of(
                        binding -> binding.getSrcId().equals(id),
                        binding -> binding.getDestId().getType().equals(BindingType.QUESTION)))
                .map(question -> (Question) question)
                .toList();
    }

    // Create
    public User createStudent(final String name) {
        return new User(name, User.Type.STUDENT);
    }
    public User createAdmin(final String name) {
        return new User(name, User.Type.ADMIN);
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
}
