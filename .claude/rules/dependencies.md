---
paths:
  - "pom.xml"
  - "trip/pom.xml"
  - "jsft/pom.xml"
---

# Stack and dependency pins

Java 25 · Jakarta EE 10 (Servlet 6.1, Faces 4.1 / Mojarra, CDI 4.1 / Weld) · PrimeFaces 15 + extensions (`jakarta`
classifier) · Jersey 3.1 (REST) · MapStruct · Lombok · Jackson · AWS SDK v2, sync clients blocking on virtual threads
(DynamoDB, SES, Secrets Manager, CloudWatch Logs, S3, CloudFront, CodePipeline; every SDK artifact excludes
`netty-nio-client` AND `apache5-client`, so the one sync `apache-client` makes client discovery unambiguous and the
only Netty in the WAR is Lettuce's) · Lettuce (**must stay 6.x** -- the build pins Netty 4.1; Lettuce 7 needs
Netty 4.2) · BCrypt (`at.favre.lib`) · PayPal server SDK · Stripe Java SDK (blocking HttpURLConnection; the key
travels per call in `RequestOptions`, never the global `Stripe.apiKey`) · Apache POI · TestNG · NightMonkeys
`imageio-heif` (chat-photo HEIC decode over the SYSTEM libheif via FFM -- needs `--enable-native-access` plus an
unversioned `libheif.so` on `java.library.path`; without it HEIC uploads get a clean "convert to JPEG" rejection and
everything else works) · metadata-extractor (EXIF orientation) · OWASP Java HTML Sanitizer (Apache-2.0 or
BSD-2-Clause, no runtime dependencies; parses and writes hand-authored page HTML for `content/PageHtml`,
`docs/migrations/html-clean.md`; keep it current, its 2026 releases are bypass fixes) · jsoup (MIT, no runtime
dependencies; the HTML5 parser behind `content/HtmlCheck`, which validates HTML stored and shown as written; moving the
cleaners onto it and dropping OWASP is medjugorje#180) · ICU4J (Unicode
License v3, MIT-style, no runtime dependencies; plural-aware message formatting for `i18n/MessageText`, since the
JDK's `MessageFormat` cannot express Croatian's three plural forms, `docs/i18n.md`).

Build-only tooling (never on the application classpath, never in the WAR): checkstyle (LGPL-2.1), JaCoCo (EPL-2.0),
and SpotBugs (engine LGPL-2.1; its Maven plugin Apache-2.0) with Find Security Bugs (LGPL-3.0). The LGPL tools are run
unmodified as build steps, which places no condition on the product; the owner approved exactly these on 2026-11-11.
A further LGPL tool, or any tool whose code would reach an artifact, needs the owner's OK first. A new LIBRARY must
still be Apache-2.0, MIT, BSD or EPL class, with its license named when it is added.

`--enable-preview` (for `StructuredTaskScope`) is set in the compiler args, the surefire argLine, the container's
`CATALINA_OPTS` AND `../medjugorje/ci/buildspec-integtest.yml`; see `.claude/rules/threading.md`.

- `trip/pom.xml`'s `annotationProcessorPaths` REPLACES the parent's: Lombok is re-listed there, and
  `lombok-mapstruct-binding` must sit between Lombok and `mapstruct-processor`, or the mappers generate empty.
- A `--` inside an XML comment makes a POM unparseable (it is easy to type when writing "--" for a dash); the
  xhtml `--` guard does not cover pom files.
