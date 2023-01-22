package org.paulsens.trip.model;

import java.time.LocalDateTime;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class StatusTest {

    @Test
    public void testSetValue() throws Exception {
        final Status status = Status.builder().build();
        assertEquals(status.getValue(), Status.StatusValue.TODO);
        assertNull(status.getNotes());
        assertNull(status.getOwner());
        assertEquals(status.getPriority(), Status.Priority.NORMAL);
        assertEquals(status.getVisibility(), Status.Visibility.USER);
        final LocalDateTime createdDate = status.getLastUpdate();
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        assertTrue(createdDate.isBefore(LocalDateTime.now()));
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        // Set a value
        status.setValue(Status.StatusValue.DONE);
        assertTrue(createdDate.isBefore(status.getLastUpdate()));
        assertEquals(status.getValue(), Status.StatusValue.DONE);
        // Set another value
        final LocalDateTime prevDate = status.getLastUpdate();
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        status.setValue("IN_PROGRESS");
        assertTrue(prevDate.isBefore(status.getLastUpdate()));
        assertEquals(status.getValue(), Status.StatusValue.IN_PROGRESS);
    }

    @Test
    public void testSetNotes() throws Exception {
        final String notes = RandomData.genAlpha(22);
        final Status status = Status.builder()
                .notes(notes)
                .build();
        final LocalDateTime createdDate = status.getLastUpdate();
        assertEquals(status.getNotes(), notes);
        final String newNotes = RandomData.genAlpha(33);
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        status.setNotes(newNotes);
        assertTrue(createdDate.isBefore(status.getLastUpdate()));
        assertEquals(status.getNotes(), newNotes);
    }

    @Test
    public void testSetOwner() throws Exception {
        final Person.Id origOwner = Person.Id.newInstance();
        final Status status = Status.builder()
                .owner(origOwner)
                .build();
        final LocalDateTime createdDate = status.getLastUpdate();
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        assertEquals(status.getOwner(), origOwner);
        final Person.Id owner = Person.Id.newInstance();
        status.setOwner(owner);
        assertEquals(status.getOwner(), owner);
        assertEquals(status.getLastUpdate(), createdDate);
    }

    @Test
    public void testSetPriority() throws Exception {
        final Status status = Status.builder()
                .priority(Status.Priority.HIGH)
                .build();
        final LocalDateTime createdDate = status.getLastUpdate();
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        assertEquals(status.getPriority(), Status.Priority.HIGH);
        status.setPriority(Status.Priority.LOW);
        assertEquals(status.getPriority(), Status.Priority.LOW);
        assertEquals(status.getLastUpdate(), createdDate);
    }

    @Test
    public void testSetVisibility() throws Exception {
        final Status status = Status.builder()
                .visibility(Status.Visibility.ADMIN)
                .build();
        final LocalDateTime createdDate = status.getLastUpdate();
        Thread.sleep(1);     // To ensure update time changes by at least 1ms
        assertEquals(status.getVisibility(), Status.Visibility.ADMIN);
        status.setVisibility(Status.Visibility.USER);
        assertEquals(status.getVisibility(), Status.Visibility.USER);
        assertEquals(status.getLastUpdate(), createdDate);
    }

    @Test
    public void canConvertToJson() throws Exception {
        final Status before = Status.builder()
                .value(Status.StatusValue.IN_PROGRESS)
                .notes(RandomData.genAlpha(12))
                .owner(Person.Id.newInstance())
                .priority(Status.Priority.CRITICAL)
                .visibility(Status.Visibility.ADMIN).build();
        final String json = DAO.getInstance().getMapper().writeValueAsString(before);
        final Status after = DAO.getInstance().getMapper().readValue(json, Status.class);
        assertEquals(after, before);
    }

    @Test
    public void testTestEquals() {
        EqualsVerifier.forClass(TodoStatus.class).verify();
    }
}