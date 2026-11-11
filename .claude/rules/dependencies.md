---
paths:
  - "pom.xml"
  - "trip/pom.xml"
  - "jsft/pom.xml"
---

# Stack and dependency pins

Java 25 · Jakarta EE 10 (Servlet 6.1, Faces 4.1 / Mojarra, CDI 4.1 / Weld) · PrimeFaces 15 + extensions (`jakarta`
classifier) · Jersey 3.1 (REST) · MapStruct · Lombok · Jackson · AWS SDK v2, sync clients blocking on virtual threads
(DynamoDB, SES, Secrets Manager, CloudWatch Logs, S3, CloudFront, CodePipeline) · Lettuce (**must stay 6.x** -- the
build pins Netty 4.1; Lettuce 7 needs Netty 4.2) · BCrypt (`at.favre.lib`) · PayPal server SDK · Stripe Java SDK
(blocking HttpURLConnection; the key travels per call in `RequestOptions`, never the global `Stripe.apiKey`) · Apache
POI · TestNG · NightMonkeys `imageio-heif` (chat-photo HEIC decode over the SYSTEM libheif via FFM -- needs
`--enable-native-access` plus an unversioned `libheif.so` on `java.library.path`; without it HEIC uploads get a clean
"convert to JPEG" rejection and everything else works) · metadata-extractor (EXIF orientation).

Build-only tooling (never on the application classpath, never in the WAR): checkstyle (LGPL-2.1), JaCoCo (EPL-2.0),
and SpotBugs (engine LGPL-2.1; its Maven plugin Apache-2.0) with Find Security Bugs (LGPL-3.0). The LGPL tools are run
unmodified as build steps, which places no condition on the product. A new LIBRARY must still be Apache-2.0, MIT, BSD or
EPL class, with its license named when it is added.

`--enable-preview` (for `StructuredTaskScope`) is set in the compiler args, the surefire argLine, the container's
`CATALINA_OPTS` AND `../medjugorje/ci/buildspec-integtest.yml`; see `.claude/rules/threading.md`.
