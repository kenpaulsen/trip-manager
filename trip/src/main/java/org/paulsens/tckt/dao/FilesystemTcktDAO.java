package org.paulsens.tckt.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Id;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.trip.dynamo.DAO;

@Slf4j
public class FilesystemTcktDAO {
    private final static FilesystemTcktDAO INSTANCE = new FilesystemTcktDAO();
    private final static ObjectMapper mapper = DAO.getInstance().getMapper();

    public static FilesystemTcktDAO getInstance() {
        return INSTANCE;
    }

    public void saveAnswers(final String filename, Collection<Answer> answers) throws IOException {
        saveThings(filename, answers);
    }

    public Map<Answer.Id, Answer> loadAnswers(final String filename) {
        final Map<Answer.Id, Answer> result = new ConcurrentHashMap<>();
        loadThings(filename, line -> readToMap(result, line, Answer.class, Answer::getId));
        return result;
    }

    public void saveBindings(final String filename, Collection<Binding> bindings) throws IOException {
        saveThings(filename, bindings);
    }

    public Map<Binding.Id, Binding> loadBindings(final String filename) {
        final Map<Binding.Id, Binding> result = new ConcurrentHashMap<>();
            loadThings(filename, line -> readToMap(result, line, Binding.class, Binding::getId));
        return result;
    }

    public void saveCourses(final String filename, Collection<Course> courses) throws IOException {
        saveThings(filename, courses);
    }

    public Map<Course.Id, Course> loadCourses(final String filename) {
        final Map<Course.Id, Course> result = new ConcurrentHashMap<>();
        loadThings(filename, line -> readToMap(result, line, Course.class, Course::getId));
        return result;
    }

    public void saveQuestions(final String filename, Collection<Question> questions) throws IOException {
        saveThings(filename, questions);
    }

    public Map<Question.Id, Question> loadQuestions(final String filename) {
        final Map<Question.Id, Question> result = new ConcurrentHashMap<>();
        loadThings(filename, line -> readToMap(result, line, Question.class, Question::getId));
        return result;
    }

    public void saveTickets(final String filename, Collection<Ticket> tickets) throws IOException {
        saveThings(filename, tickets);
    }

    public Map<Ticket.Id, Ticket> loadTickets(final String filename) {
        final Map<Ticket.Id, Ticket> result = new ConcurrentHashMap<>();
        loadThings(filename, line -> readToMap(result, line, Ticket.class, Ticket::getId));
        return result;
    }

    public void saveUsers(final String filename, Collection<User> users) throws IOException {
        saveThings(filename, users);
    }

    public Map<User.Id, User> loadUsers(final String filename) {
        final Map<User.Id, User> result = new ConcurrentHashMap<>();
        loadThings(filename, line -> readToMap(result, line, User.class, User::getId));
        return result;
    }

    private <T> void saveThings(final String filename, Collection<T> things) throws IOException {
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            for (final T thing : things) {
                // FIXME: don't overwrite old??
                writer.write(mapper.writeValueAsString(thing));
                writer.newLine();
            }
        }
    }

    private void loadThings(final String filename, final Consumer<String> lineConsumer) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line = reader.readLine();
            while (line != null) {
                lineConsumer.accept(line);
                line = reader.readLine();
            }
        } catch (final IOException ex) {
            log.warn("Error while loading '" + filename + "'!", ex);
        }
    }

    private <ID extends Id, T> void readToMap(
            final Map<ID, T> map, final String line, final Class<T> type, final Function<T, ID> idGetter) {
        try {
            final T thing = mapper.readValue(line, type);
            map.put(idGetter.apply(thing), thing);
        } catch (final IOException ex) {
            log.warn("Error reading line from file: " + line, ex);
        }
    }
}
