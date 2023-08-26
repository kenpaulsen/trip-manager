package org.paulsens.tckt.dao;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.paulsens.tckt.model.Answer;
import org.paulsens.tckt.model.Binding;
import org.paulsens.tckt.model.Course;
import org.paulsens.tckt.model.Question;
import org.paulsens.tckt.model.Ticket;
import org.paulsens.tckt.model.User;
import org.paulsens.tckt.testutil.FileUtils;
import org.paulsens.tckt.testutil.TestData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class FilesystemPersistenceTest {
    @BeforeClass
    public static void setup() {
        // Cleanup from prev runs
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.ANSWER_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.ANSWER_PATH);
        }
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.BINDING_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.BINDING_PATH);
        }
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.COURSES_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.COURSES_PATH);
        }
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.QUESTIONS_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.QUESTIONS_PATH);
        }
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.TICKETS_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.TICKETS_PATH);
        }
        if (FileUtils.fileExists(TestData.BASE_PATH + TestData.USERS_PATH)) {
            FileUtils.deleteFile(TestData.BASE_PATH + TestData.USERS_PATH);
        }
    }

    @Test
    public void answersCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<Answer> answers = Set.of(TestData.randAnswer(), TestData.randAnswer(), TestData.randAnswer());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.ANSWER_PATH;
        for (final Answer ans : answers) {
            persistence.cacheAnswer(relPath, ans);
        }
        persistence.saveAnswers(relPath);
        final Map<Answer.Id, Answer> lowLevelAnswers =
                FilesystemTcktDAO.getInstance().loadAnswers(TestData.BASE_PATH + relPath);
        final Map<Answer.Id, Answer> restored = persistence.getAnswers(relPath);
        Assert.assertEquals(restored.size(), lowLevelAnswers.size());
        Assert.assertEquals(restored.size(), answers.size());
        for (final Answer.Id id : restored.keySet()) {
            Assert.assertTrue(answers.contains(restored.get(id)));
        }
    }

    @Test
    public void bindingsCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<Binding> bindings = Set.of(TestData.randBinding(), TestData.randBinding(), TestData.randBinding());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.BINDING_PATH;
        for (final Binding ans : bindings) {
            persistence.cacheBinding(relPath, ans);
        }
        persistence.saveBindings(relPath);
        final Map<Binding.Id, Binding> lowLevelBindings =
                FilesystemTcktDAO.getInstance().loadBindings(TestData.BASE_PATH + relPath);
        final Map<Binding.Id, Binding> restored = persistence.getBindings(relPath);
        Assert.assertEquals(restored.size(), lowLevelBindings.size());
        Assert.assertEquals(restored.size(), bindings.size());
        for (final Binding.Id id : restored.keySet()) {
            Assert.assertTrue(bindings.contains(restored.get(id)));
        }
    }

    @Test
    public void coursesCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<Course> courses = Set.of(TestData.randCourse(), TestData.randCourse(), TestData.randCourse());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.COURSES_PATH;
        for (final Course ans : courses) {
            persistence.cacheCourse(relPath, ans);
        }
        persistence.saveCourses(relPath);
        final Map<Course.Id, Course> lowLevelCourses =
                FilesystemTcktDAO.getInstance().loadCourses(TestData.BASE_PATH + relPath);
        final Map<Course.Id, Course> restored = persistence.getCourses(relPath);
        Assert.assertEquals(restored.size(), lowLevelCourses.size());
        Assert.assertEquals(restored.size(), courses.size());
        for (final Course.Id id : restored.keySet()) {
            Assert.assertTrue(courses.contains(restored.get(id)));
        }
    }

    @Test
    public void questionsCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<Question> questions = Set.of(TestData.randQuestion(), TestData.randQuestion(), TestData.randQuestion());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.QUESTIONS_PATH;
        for (final Question ans : questions) {
            persistence.cacheQuestion(relPath, ans);
        }
        persistence.saveQuestions(relPath);
        final Map<Question.Id, Question> lowLevelQuestions =
                FilesystemTcktDAO.getInstance().loadQuestions(TestData.BASE_PATH + relPath);
        final Map<Question.Id, Question> restored = persistence.getQuestions(relPath);
        Assert.assertEquals(restored.size(), lowLevelQuestions.size());
        Assert.assertEquals(restored.size(), questions.size());
        for (final Question.Id id : restored.keySet()) {
            Assert.assertTrue(questions.contains(restored.get(id)));
        }
    }

    @Test
    public void ticketsCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<Ticket> tickets = Set.of(TestData.randTicket(), TestData.randTicket(), TestData.randTicket());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.TICKETS_PATH;
        for (final Ticket ans : tickets) {
            persistence.cacheTicket(relPath, ans);
        }
        persistence.saveTickets(relPath);
        final Map<Ticket.Id, Ticket> lowLevelTickets =
                FilesystemTcktDAO.getInstance().loadTickets(TestData.BASE_PATH + relPath);
        final Map<Ticket.Id, Ticket> restored = persistence.getTickets(relPath);
        Assert.assertEquals(restored.size(), lowLevelTickets.size());
        Assert.assertEquals(restored.size(), tickets.size());
        for (final Ticket.Id id : restored.keySet()) {
            Assert.assertTrue(tickets.contains(restored.get(id)));
        }
    }

    @Test
    public void usersCanBeSavedAndLoadedFromDisk() throws IOException {
        final Set<User> users = Set.of(TestData.randUser(), TestData.randUser(), TestData.randUser());
        final FilesystemPersistence persistence = new FilesystemPersistence(TestData.BASE_PATH);
        final String relPath = TestData.USERS_PATH;
        for (final User ans : users) {
            persistence.cacheUser(relPath, ans);
        }
        persistence.saveUsers(relPath);
        final Map<User.Id, User> lowLevelUsers =
                FilesystemTcktDAO.getInstance().loadUsers(TestData.BASE_PATH + relPath);
        final Map<User.Id, User> restored = persistence.getUsers(relPath);
        Assert.assertEquals(restored.size(), lowLevelUsers.size());
        Assert.assertEquals(restored.size(), users.size());
        for (final User.Id id : restored.keySet()) {
            Assert.assertTrue(users.contains(restored.get(id)));
        }
    }
}