package org.paulsens.trip.action;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.TemplateRecord;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The store-failure legs of the content beans: a public page must render (empty) and an admin action must
 * answer false -- never a 500 -- when the tables are unreachable.
 */
public class ContentFailurePathsTest {

    private static ContentCommands contentAsAdmin() {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(ContentFailurePathsTest::adminCaller);
        return content;
    }

    private static TemplateCommands templatesAsAdmin() {
        final TemplateCommands commands = new TemplateCommands();
        commands.setCallerSource(ContentFailurePathsTest::adminCaller);
        return commands;
    }

    private static Caller adminCaller() {
        return new Caller(Person.Id.from("fail-tester"), true,
                new AuditActor("fail@example.com", "fail-tester"), new PrivilegeCommands());
    }

    private static ContentInstance instance() {
        return new ContentInstance("fp-id", "fp.section", "T", "tpl", 1, new HashMap<>(), null, 0, 1,
                null, null);
    }

    @Test
    public void contentBeanFailsSoftWhenTheStoreThrows() {
        final ContentCommands content = contentAsAdmin();
        final ContentInstance edit = instance();
        final DAO angry = Mockito.mock(DAO.class,
                invocation -> {
                    throw new IllegalStateException("store down");
                });
        try (MockedStatic<DAO> dao = Mockito.mockStatic(DAO.class)) {
            dao.when(DAO::getInstance).thenReturn(angry);
            Assert.assertTrue(content.getForSection("fp.section").isEmpty(), "page renders empty, not 500");
            Assert.assertNotNull(content.getContent("fp-id"), "blank instance, not an exception");
            Assert.assertFalse(content.saveContent(edit));
            Assert.assertFalse(content.deleteContent("fp-id"));
            Assert.assertTrue(content.getHistory("fp-id").isEmpty());
            Assert.assertFalse(content.restoreContent("fp-id", 1));
            Assert.assertEquals(content.render(edit), "", "render failure yields nothing, not a broken page");
        }
    }

    @Test
    public void restoreSaveFailureAnswersFalse() {
        // The record loads, then the save itself blows up -- the leg after the version carry-over.
        final ContentCommands content = contentAsAdmin();
        final ContentInstance current = instance();
        final DAO dao = Mockito.mock(DAO.class);
        Mockito.when(dao.getContentRecord("fp-id"))
                .thenReturn(Optional.of(new ContentRecord("fp-id", current, List.of())));
        Mockito.when(dao.saveContent(Mockito.any(), Mockito.anyInt()))
                .thenThrow(new IllegalStateException("save refused"));
        try (MockedStatic<DAO> mocked = Mockito.mockStatic(DAO.class)) {
            mocked.when(DAO::getInstance).thenReturn(dao);
            Assert.assertFalse(content.restoreContent("fp-id", 1));
        }
    }

    @Test
    public void templateBeanFailsSoftWhenTheStoreThrows() {
        final TemplateCommands commands = templatesAsAdmin();
        final ContentTemplate edit = new ContentTemplate("fp-tpl", 0, "N", null, "b", null, null, null);
        final DAO angry = Mockito.mock(DAO.class,
                invocation -> {
                    throw new IllegalStateException("store down");
                });
        try (MockedStatic<DAO> dao = Mockito.mockStatic(DAO.class)) {
            dao.when(DAO::getInstance).thenReturn(angry);
            Assert.assertTrue(commands.getTemplates().isEmpty());
            Assert.assertEquals(commands.getTemplate("fp-tpl").getId(), "fp-tpl", "blank, not an exception");
            Assert.assertEquals(commands.getTemplate("fp-tpl", 2).getId(), "fp-tpl");
            Assert.assertFalse(commands.saveTemplate(edit));
            Assert.assertTrue(commands.getHistory("fp-tpl").isEmpty());
            Assert.assertFalse(commands.restoreTemplate("fp-tpl", 1));
            Assert.assertEquals(commands.installStarterTemplates(), 0, "existence checks fail closed");
            Assert.assertFalse(commands.deleteTemplate("fp-tpl"),
                    "an unanswerable reference check refuses the delete");
        }
    }

    @Test
    public void templateDeleteFailureAfterChecksAnswersFalse() {
        // References answer clean, the delete itself throws -- the last leg.
        final TemplateCommands commands = templatesAsAdmin();
        final DAO dao = Mockito.mock(DAO.class);
        Mockito.when(dao.getAllContentRecords()).thenReturn(List.of());
        Mockito.when(dao.deleteTemplate("fp-tpl")).thenThrow(new IllegalStateException("boom"));
        try (MockedStatic<DAO> mocked = Mockito.mockStatic(DAO.class)) {
            mocked.when(DAO::getInstance).thenReturn(dao);
            Assert.assertFalse(commands.deleteTemplate("fp-tpl"));
        }
    }

    @Test
    public void restoreTemplateSaveFailureAnswersFalse() {
        final TemplateCommands commands = templatesAsAdmin();
        final ContentTemplate current = new ContentTemplate("fp-tpl", 2, "N", null, "b", null, null, null);
        final DAO dao = Mockito.mock(DAO.class);
        Mockito.when(dao.getTemplateRecord("fp-tpl"))
                .thenReturn(Optional.of(new TemplateRecord("fp-tpl", current, List.of())));
        Mockito.when(dao.saveTemplate(Mockito.any(), Mockito.anyInt()))
                .thenThrow(new IllegalStateException("save refused"));
        try (MockedStatic<DAO> mocked = Mockito.mockStatic(DAO.class)) {
            mocked.when(DAO::getInstance).thenReturn(dao);
            Assert.assertFalse(commands.restoreTemplate("fp-tpl", 2));
        }
    }
}
