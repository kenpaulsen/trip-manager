package org.paulsens.tckt.testutil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.BindingType;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Id;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.trip.util.RandomData;

public class TestData {
    private static final Map<Integer, Supplier<Id>> ID_SUPPLIERS = Map.of(
            0, Answer.Id::newId,
            1, Binding.Id::newId,
            2, Course.Id::newId,
            3, Question.Id::newId,
            4, Ticket.Id::newId,
            5, User.Id::newId);
    public static final String BASE_PATH = "/tmp";
    public static final String ANSWER_PATH = "/answers.tckt";
    public static final String BINDING_PATH = "/bindings.tckt";
    public static final String COURSES_PATH = "/courses.tckt";
    public static final String QUESTIONS_PATH = "/questions.tckt";
    public static final String TICKETS_PATH = "/tickets.tckt";
    public static final String USERS_PATH = "/users.tckt";

    public static Answer randAnswer() {
        final String value = RandomData.genAlpha(13);
        final LocalDateTime submitted = LocalDateTime.now().minusDays(RandomData.randomInt(10));
        final Question.Id questionId = Question.Id.newId();
        final User.Id userId = User.Id.newId();
        final Course.Id courseId = Course.Id.newId();
        final Ticket.Id ticketId = Ticket.Id.newId();
        return new Answer(Answer.Id.newId(), value, submitted, questionId, userId, courseId, ticketId);
    }

    public static Binding randBinding() {
        final Id srcId = randId();
        final Id destId = randId();
        final BindingType srcType = RandomData.randomEnum(BindingType.class);
        final BindingType destType = RandomData.randomEnum(BindingType.class);
        return new Binding(Binding.Id.newId(), srcId.getValue(), destId.getValue(), srcType, destType);
    }

    public static Course randCourse() {
        final String name = RandomData.genAlpha(23);
        final User.Id teacherId = User.Id.newId();
        final Year year = Year.now().minusYears(RandomData.randomInt(3));
        return new Course(Course.Id.newId(), name, teacherId, year);
    }

    public static Question randQuestion() {
        final String question = RandomData.genAlpha(23);
        final String answerKey = RandomData.genAlpha(13);
        final List<Question.Choice> choices = List.of(randChoice(), randChoice(), randChoice());
        return new Question(Question.Id.newId(), question, answerKey, choices);
    }

    public static Ticket randTicket() {
        User.Id owner = User.Id.newId();
        LocalDate date = LocalDate.now().minusDays(RandomData.randomInt(30));
        String title = RandomData.genAlpha(17);
        return new Ticket(Ticket.Id.newId(), owner, date, title);
    }

    public static User randUser() {
        final String first = RandomData.genAlpha(7);
        final String last = RandomData.genAlpha(9);
        final String pass = RandomData.genAlpha(13);
        final User.Type type = RandomData.randomEnum(User.Type.class);
        return new User(User.Id.newId(), first, last, pass, type);
    }

    public static Question.Choice randChoice() {
        final String name = RandomData.genAlpha(8);
        final String description = RandomData.genAlpha(16);
        return new Question.Choice(name, description);
    }

    public static Id randId() {
        return ID_SUPPLIERS.get(RandomData.randomInt(6)).get();
    }
}
