package org.paulsens.tckt.dao;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.tckt.testutil.TestData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FilesystemTcktDAOTest {
    @Test
    public void answersCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<Answer> answers = List.of(TestData.randAnswer(), TestData.randAnswer(), TestData.randAnswer());
        FilesystemTcktDAO.getInstance().saveAnswers("/tmp/someanswers.json", answers);
        final Map<Answer.Id, Answer> restoredAnswers =
                FilesystemTcktDAO.getInstance().loadAnswers("/tmp/someanswers.json");
        Assert.assertEquals(restoredAnswers.size(), answers.size());
    }

    @Test
    public void bindingsCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<Binding> bindings = List.of(TestData.randBinding(), TestData.randBinding(), TestData.randBinding());
        FilesystemTcktDAO.getInstance().saveBindings("/tmp/somebindings.json", bindings);
        final Map<Binding.Id, Binding> restoredBindings =
                FilesystemTcktDAO.getInstance().loadBindings("/tmp/somebindings.json");
        Assert.assertEquals(restoredBindings.size(), bindings.size());
    }

    @Test
    public void coursesCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<Course> courses = List.of(TestData.randCourse(), TestData.randCourse(), TestData.randCourse());
        FilesystemTcktDAO.getInstance().saveCourses("/tmp/somecourses.json", courses);
        final Map<Course.Id, Course> restoredCourses =
                FilesystemTcktDAO.getInstance().loadCourses("/tmp/somecourses.json");
        Assert.assertEquals(restoredCourses.size(), courses.size());
    }

    @Test
    public void questionsCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<Question> questions = List.of(TestData.randQuestion(), TestData.randQuestion(), TestData.randQuestion());
        FilesystemTcktDAO.getInstance().saveQuestions("/tmp/somequestions.json", questions);
        final Map<Question.Id, Question> restoredQuestions =
                FilesystemTcktDAO.getInstance().loadQuestions("/tmp/somequestions.json");
        Assert.assertEquals(restoredQuestions.size(), questions.size());
    }

    @Test
    public void ticketsCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<Ticket> tickets = List.of(TestData.randTicket(), TestData.randTicket(), TestData.randTicket());
        FilesystemTcktDAO.getInstance().saveTickets("/tmp/sometickets.json", tickets);
        final Map<Ticket.Id, Ticket> restoredTickets =
                FilesystemTcktDAO.getInstance().loadTickets("/tmp/sometickets.json");
        Assert.assertEquals(restoredTickets.size(), tickets.size());
    }

    @Test
    public void usersCanBeSavedAndLoadedFromDisk() throws IOException {
        final List<User> users = List.of(TestData.randUser(), TestData.randUser(), TestData.randUser());
        FilesystemTcktDAO.getInstance().saveUsers("/tmp/someusers.json", users);
        final Map<User.Id, User> restoredUsers =
                FilesystemTcktDAO.getInstance().loadUsers("/tmp/someusers.json");
        Assert.assertEquals(restoredUsers.size(), users.size());
    }
}