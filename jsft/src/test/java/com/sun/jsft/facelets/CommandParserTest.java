package com.sun.jsft.facelets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.Assert;
import org.junit.Test;


/**
 * @author Ken Paulsen (kenapaulsen@gmail.com)
 */
public class CommandParserTest {

    @Test
    public void testReadUntilWithHtmlComments() throws IOException {
	// Run Test...
	String testStr = "<!-- This is a  test.  -->";
	String result = testReadUntil(testStr, "-->", false);

	// Verify result
	Assert.assertEquals(testStr, result);
    }

    @Test
    public void testReadUntilWithHtmlComments2() throws IOException {
	// Run Test...
	String testStr = "abc <!-- This is a  test.  --> 123";
	String expStr  = "abc <!-- This is a  test.  -->";
	String result = testReadUntil(testStr, "-->", false);

	// Verify result
	Assert.assertEquals(expStr, result);
    }

    @Test
    public void testReadUntilWithHtmlComments3() throws IOException {
	// Run Test...
	String testStr = "abc <!-- This is a  test.  -->end here";
	String expStr  = "abc end";
	String result = testReadUntil(testStr, "end", true);

	// Verify result
	Assert.assertEquals(expStr, result);
    }

    @Test
    public void testReadUntilWithHtmlComments4() throws IOException {
	// Run Test...
	String testStr = "abc <!-- This is a \n\ntest.  --> end here";
	String expStr  = "abc  end here";
	String result = testReadUntil(testStr, "end here", true);

	// Verify result
	Assert.assertEquals(expStr, result);
    }

    @Test
    public void testReadUntilWithHtmlComments5() throws IOException {
	// Run Test... note: comments are not skipped inside quotes
	String testStr = "abc '<!-- This is a test. -->' end here foo";
	String expStr  = "abc '<!-- This is a test. -->' end here";
	String result = testReadUntil(testStr, "end here", true);

	// Verify result
	Assert.assertEquals(expStr, result);
    }

    @Test
    public void testReadUntilWithHtmlComments6() throws IOException {
	// Run Test... note: comments are not skipped inside quotes
	String testStr = "abc \"<!-- This is a \n\ntest. -->\" end here foo";
	String expStr  = "abc \"<!-- This is a \n\ntest. -->\" end here";
	String result = testReadUntil(testStr, "end here", true);

	// Verify result
	Assert.assertEquals(expStr, result);
    }

    /**
     *	<p> Help execute test readUntil test case.</p>
     */
    private String testReadUntil(String testStr, String endStr, boolean skipComments) throws IOException {
	// Create CommandParser w/ testStr
	CommandParser parser = new CommandParser(getInputStream(testStr));
	parser.open();

	// Read until the end of the comment
	String result = parser.readUntil(endStr, skipComments);

	// Close the parser
	parser.close();

	return result;
    }

    /**
     *	<p> This converts the given <code>String</code> into an
     *	    <code>InputStream</code>.</p>
     */
    private InputStream getInputStream(String data) {
	return new ByteArrayInputStream(data.getBytes());
    }
}
