package org.paulsens.trip.media;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

/**
 * What a library upload is served as. The rule that matters: nothing that can run as a page (HTML, SVG, XML,
 * script) is ever served inline from the media host every organization shares, whatever its name or the
 * browser's claim.
 */
public class UploadTypeTest {

    @Test
    public void imagesAndPdfsAreRecognizedFromTheirBytesWhateverTheyAreNamed() {
        assertEquals(UploadType.of("downloads/photo.html", PhotoFixtures.jpeg(4, 4)),
                new UploadType("image/jpeg", false));
        assertEquals(UploadType.of("a.bin", PhotoFixtures.png(4, 4, true)), new UploadType("image/png", false));
        assertEquals(UploadType.of("a", PhotoFixtures.gif(4, 4)), new UploadType("image/gif", false));
        assertEquals(UploadType.of("guide", ascii("%PDF-1.7\n...")), new UploadType("application/pdf", false));
    }

    @Test
    public void anythingThatCouldRunIsDownloadOnly() {
        final UploadType download = new UploadType(UploadType.DOWNLOAD, true);
        assertEquals(UploadType.of("guide.html", ascii("<!doctype html><script>alert(1)</script>")), download);
        assertEquals(UploadType.of("logo.svg", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>")),
                download, "SVG is an image that carries script");
        assertEquals(UploadType.of("data.xml", ascii("<?xml version=\"1.0\"?><x/>")), download);
        assertEquals(UploadType.of("app.js", ascii("alert(1)")), download);
        assertEquals(UploadType.of("guide.pdf", ascii("<html>not really a pdf</html>")), download,
                "the NAME proves nothing: a .pdf that is not one is just a download");
        assertEquals(UploadType.of("photo.jpg", ascii("<html>")), download);
        assertEquals(UploadType.of("notes.txt", ascii("plain words")), download);
        assertEquals(UploadType.of("plan.docx", ascii("PK\u0003\u0004")), download, "stored, never refused");
        assertEquals(UploadType.of(null, null), download);
        assertEquals(UploadType.of("no-extension", new byte[0]), download);
        assertEquals(UploadType.of("pdf", ascii("%PD")), download, "shorter than the signature");
    }

    @Test
    public void videoAndAudioPlayInlineByExtension() {
        assertEquals(UploadType.of("downloads/Welcome.MP4", ascii("....ftypisom")), new UploadType("video/mp4", false));
        assertEquals(UploadType.of("clip.mov", new byte[0]), new UploadType("video/quicktime", false));
        assertEquals(UploadType.of("clip.webm", new byte[0]), new UploadType("video/webm", false));
        assertEquals(UploadType.of("talk.mp3", new byte[0]), new UploadType("audio/mpeg", false));
        assertEquals(UploadType.of("talk.m4a", new byte[0]), new UploadType("audio/mp4", false));
        assertEquals(UploadType.of("talk.wav", new byte[0]), new UploadType("audio/wav", false));
    }

    /** A direct upload's bytes never pass through the server, so only an inert type may be signed for it. */
    @Test
    public void aDirectUploadMayOnlyDeclareAnInertType() {
        assertTrue(UploadType.mayDeclare("image/jpeg"));
        assertTrue(UploadType.mayDeclare("Application/PDF"));
        assertTrue(UploadType.mayDeclare("video/mp4; codecs=avc1"));
        assertTrue(UploadType.mayDeclare(UploadType.DOWNLOAD));
        assertFalse(UploadType.mayDeclare("text/html"));
        assertFalse(UploadType.mayDeclare("text/html; charset=utf-8"));
        assertFalse(UploadType.mayDeclare("image/svg+xml"));
        assertFalse(UploadType.mayDeclare("application/xhtml+xml"));
        assertFalse(UploadType.mayDeclare("text/javascript"));
        assertFalse(UploadType.mayDeclare("text/plain"));
        assertFalse(UploadType.mayDeclare(""));
        assertFalse(UploadType.mayDeclare(null));
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
