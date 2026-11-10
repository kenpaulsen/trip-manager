---
paths:
  - "trip/src/main/java/org/paulsens/trip/web/SecurityHeadersFilter.java"
  - "trip/src/main/java/org/paulsens/trip/web/ContentSecurityPolicy.java"
  - "trip/src/main/java/org/paulsens/trip/web/CspReportServlet.java"
---

# HTTP security headers

- `web/SecurityHeadersFilter` + `web/ContentSecurityPolicy` + `web/CspReportServlet` -- the HTTP security headers
  (HSTS, nosniff, X-Frame-Options, the CSP as report-only first) and where browsers report CSP violations. Read
  `docs/security-headers.md` before adding a foreign script, frame, font or style source to any page.
