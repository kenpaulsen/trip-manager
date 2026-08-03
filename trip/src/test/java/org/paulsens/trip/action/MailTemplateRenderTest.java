package org.paulsens.trip.action;

import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Rendering a mail template outside JSF.
 *
 * <p>{@code previewTemplate} evaluates through {@code ELUtil.eval}, which resolves against the Faces expression
 * context. Off a Faces thread that yields null for the whole template, and {@code String.valueOf} turns null
 * into the four-character string "null" -- so the call does not throw, does not log, and returns something that
 * looks exactly like a rendered result. The mail preview endpoint returned {@code {"rendered":"null"}} for
 * every template until this was noticed, and nothing about the response said anything was wrong.
 *
 * <p>{@code renderTemplate} uses {@code renderWithoutJsf}, which is what the mail templates themselves already
 * go through and needs no Faces context at all.
 */
public class MailTemplateRenderTest {

    private final MailCommands mail = new MailCommands();

    @Test
    public void aTemplateRendersAgainstThePersonWithoutAFacesContext() {
        final Person person = Person.builder().first("Ken").last("Paulsen").build();

        Assert.assertEquals(mail.renderTemplate(person, "Hello #{to.first} #{to.last}!"), "Hello Ken Paulsen!");
    }

    @Test
    public void aTemplateWithNoExpressionsComesBackUnchanged() {
        Assert.assertEquals(mail.renderTemplate(Person.builder().build(), "Just text."), "Just text.");
    }

    @Test
    public void theRenderedResultIsNeverTheLiteralStringNull() {
        // The exact symptom of the Faces-context path being used off a Faces thread. If this ever comes back,
        // the endpoint is silently serving unrendered output again.
        final Person person = Person.builder().first("Ken").build();

        Assert.assertNotEquals(mail.renderTemplate(person, "#{to.first}"), "null");
        Assert.assertEquals(mail.renderTemplate(person, "#{to.first}"), "Ken");
    }

    @Test
    public void aNullTemplateRendersEmptyRatherThanThrowing() {
        Assert.assertEquals(mail.renderTemplate(Person.builder().build(), null), "");
    }

    @Test
    public void aTypoInATemplateIsLoudRatherThanSilentlyBlank() {
        // renderWithoutJsf documents this deliberately: a bad expression throws so the first send surfaces it,
        // rather than quietly mailing everyone a message with a hole in it. The resource turns it into a 422.
        final Person person = Person.builder().first("Ken").build();

        Assert.assertThrows(RuntimeException.class,
                () -> mail.renderTemplate(person, "Hello #{to.noSuchProperty}"));
    }
}
