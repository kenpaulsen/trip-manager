# HTTP security headers -- read before touching `SecurityHeadersFilter`, the CSP, or a page that loads something new

Every response from the site carries four security headers, set by `web/SecurityHeadersFilter`, declared second
in the live `web.xml` (the private repo's `webapp/WEB-INF/web.xml`, right after `SessionRecovery`). The policy
string is built by `web/ContentSecurityPolicy`; violation reports land in `web/CspReportServlet` at
`/csp-report`. Unit tests: `SecurityHeadersFilterTest`, `ContentSecurityPolicyTest`, `CspReportServletTest`.
Browser test: `../medjugorje/webtest/.../SecurityHeadersPwIT`, whose sweeps drive the busiest pages on the shared
host, an organization's site (`acme.localhost`) and the marketing host (`www.localhost`) with the console watched
and fail on any report-only violation.

## Where the headers live, and why there

Three places could set them. The choice is the application, for every header:

| Option | Why not (or why) |
|---|---|
| ALB listener response-header rules (CDK) | The 443 listener is the resource CodeDeploy rewrites on every blue/green deployment; `AppStack` already keeps extra certificates OFF the listener resource for that reason. A CloudFormation change to the listener's attributes races the deployment controller over the same resource. It would also leave the local container and the webtest suite without the headers, so nothing before production would prove them. |
| Tomcat's `org.apache.catalina.filters.HttpHeaderSecurityFilter` | Does HSTS, X-Frame-Options and nosniff, but has no Content-Security-Policy, so the CSP would need a second mechanism anyway. One filter is one place to read. |
| A servlet filter in the app | Runs in the container image, the embedded webtest Tomcat and production identically; the knobs are init-params in the live `web.xml`, the one descriptor this deployment already edits per environment; a unit test covers every branch and a browser test proves the allowlist against real pages. **Chosen.** |

The filter sets the headers BEFORE the chain runs, so they ride every kind of finish: a servlet, a `sendRedirect`,
the 404 page (the mapping covers `ERROR` dispatches) and a resumed chat long-poll (`ASYNC`). `setHeader` is
idempotent, so the two servlets that already set `nosniff` themselves (`TicketQrServlet`, `ExpenseReceiptServlet`)
keep doing so.

## The four headers

### `Strict-Transport-Security`

Sent only when `request.isSecure()`: behind the ALB that is what `RemoteIpValve` (`docker/conf/server.xml`; the
webtest `EmbeddedApp` installs the same valve) derives from `X-Forwarded-Proto: https`. Browsers ignore HSTS on
plain http by definition, so nothing is lost, and a local container shows the header only when asked the way the
ALB asks:

```sh
curl -sSI -H 'X-Forwarded-Proto: https' http://localhost:8080/ | grep -i strict
```

The value starts as `max-age=300`, deliberately tiny: a wrong HSTS header is one of the few things a deploy
cannot take back (browsers that have seen it refuse plain http for the whole max-age), and five minutes caps the
damage of any mistake in the first rollout.

**Raising it.** After the site has run under 300 through at least one further deploy with no https complaint
(every host under the apex, every org site, the apps), set `hstsMaxAgeSeconds` to `31536000` (one year) in
`web.xml` and deploy. Nothing else changes.

**`includeSubDomains`.** Off, and stays off until every name under the apex is known to answer https. What
Route 53 holds today for `unitetrip.com` (listed 2026-11-08):

| Name | What | Serves https? |
|---|---|---|
| apex, `www` | ALIAS to the ALB | yes (ACM) |
| `*.unitetrip.com` | ALIAS to the ALB (the org sites; `acme.` is a test org) | yes (the wildcard certificate) |
| `bounce.` | MX + TXT only (SES MAIL FROM) | serves no web at all |
| `_dmarc.`, `_domainkey` names, the ACM validation CNAME | TXT/CNAME only | serve no web at all |

The media CDN is `files.visitqueenofpeace.com`, a different apex, so it is not a subdomain of this one. Nothing
listed serves plain http, so `includeSubDomains` looks safe; the reason to wait is that the wildcard makes EVERY
future label a web host, and a future subdomain that cannot do TLS (a vendor CNAME, say) would be unreachable for
a year. Turn it on (`hstsIncludeSubDomains=true`) only together with that understanding, after the max-age raise.
No `preload`: it is a one-way door run by a third party, and the site has no need of it.

### `X-Content-Type-Options: nosniff`

On every response. Every content type the server sends is explicit: Mojarra and the default servlet type
resources from Tomcat's mime map plus the four `mime-mapping`s in `web.xml`; the PDF, QR, receipt, manifest and
app-association servlets each set theirs. Uploaded files are not served by this server in production at all: chat
photos, PDFs, branding and lodging images go to S3 with their `contentType` (`MediaCommands`, `ChatPhotos`,
`ExpenseReceipts`) and CloudFront serves them from there, checked 2026-11-08 (`image/jpeg` for a photo,
`application/pdf` for a guide). The one upload the server itself serves, an expense receipt, already sent `nosniff`.

**The stored type of a library upload is the server's decision, never the browser's (2026-11,
`media/UploadType`).** The media host (`files.` under the main domain) is same-site with the main site and shared
by every organization, and the Documents dialog (`/media-upload`) and the media library take files from an
organization's own media editors. Storing the claimed type let one of them publish `guide.html` as `text/html`
there: stored script on a shared host, able to set parent-domain cookies and to pose as the platform. So
`MediaCommands.upload` reads the file's first bytes: an image (`ImageFormat`) or a PDF is stored as what it IS;
video and audio are stored by extension (a browser plays them or fails); everything else, SVG included, is
stored as `application/octet-stream` with `Content-Disposition: attachment`, so it downloads and never renders.
Nothing is refused. The direct (presigned) upload cannot be looked at, so `presignUpload` signs only an inert
declared type (`UploadType.mayDeclare`; the API answers 422 otherwise). Objects stored BEFORE this change keep
the type they were given; the media distribution's `nosniff` response-headers policy (`../medjugorje/infra`) is the
backstop for those, and a file already stored as `text/html` or `image/svg+xml` has to be re-uploaded or removed.

### `X-Frame-Options: SAMEORIGIN` and `frame-ancestors 'self'`

SAMEORIGIN rather than DENY because two pages frame their own origin: `mainTemplate.xhtml` and the mission page
frame `/pay/donate.jsf` (the PayPal button), and `trip/itinerary.xhtml` frames `/trip/transactions.jsf?print=true`.
The policy's `frame-ancestors 'self'` says the same thing for browsers that read the CSP first. Nothing frames the
site from another origin: the apps load the trip-page EMBED (`trip/tripPage.xhtml`) as a top-level document in a
web view, which neither header touches.

### `Content-Security-Policy`

Sent as `Content-Security-Policy-Report-Only` (`cspMode=report-only`) until the reports have gone quiet, then
flipped to `enforce`. The policy is one string for every page (a page-specific source is harmless on a page that
never loads it, and one string is what an operator reads in one `curl -I`). It is NOT sent on JSF resource
responses (`/jakarta.faces.resource/*`): a policy governs the document that loads them, and the header would be a
kilobyte on each of the dozens of requests a page makes.

The policy as shipped, with the reason for every source. `{media}` is the origin of `TRIP_MEDIA_BASE_URL`
(`https://files.visitqueenofpeace.com` in production; absent locally, where the local servlets serve those bytes).
Five directives (`img-src`, `style-src`, `font-src`, `frame-src`, `media-src`) allow any https source on purpose:
this site carries editor-authored content and designer-authored stylesheets that reach services nobody lists in
advance (an image, a font, an embedded video or map, an audio clip), and none of the five can run code in our
page. Scripts and connections stay narrow; that is where the protection lives.

| Directive | Sources | Why |
|---|---|---|
| `default-src` | `'self'` | Anything no directive names is same-origin. |
| `base-uri` | `'self'` | No page sets `<base>`; pinning it closes one injection shape. |
| `frame-ancestors` | `'self'` | See X-Frame-Options above. |
| `form-action` | `'self' https://www.paypal.com https://www.sandbox.paypal.com` | `pay/donate.xhtml` is a plain form posting to PayPal; every other form posts to us. Chrome also checks the REDIRECT after a form post against this list, and the PayPal checkout is a postback that answers with a redirect to the approval URL, so both PayPal hosts must be here. **Watch this one** in the reports: any other redirect-after-POST to a foreign host would show up as a `form-action` violation. |
| `script-src` | `'self' 'unsafe-inline' 'unsafe-eval' https://js.stripe.com https://*.js.stripe.com https://www.googletagmanager.com` | Mojarra and PrimeFaces render inline `<script>` blocks and `on*` attributes (`unsafe-inline`), and both run the scripts an ajax partial response carries through `eval` (`unsafe-eval`). Stripe.js is loaded by `trip/payment.xhtml` and the priced-checkout step (`PaymentProcessor.Inline.STRIPE_JS`); `*.js.stripe.com` is where Stripe starts frames for performance. The analytics tag loads from Google Tag Manager, with consent only. |
| `style-src` | `'self' 'unsafe-inline' https:` | PrimeFaces writes `style=` attributes everywhere. A trip page's own stylesheets are media-library rows the CDN serves, and a designer's sheet may `@import` a font service: the mir summerfest page pulls League Spartan and Playfair Display from Google Fonts. Any https stylesheet is allowed because a stylesheet cannot run code and the next org may pick a different service. |
| `img-src` | `'self' data: blob: https:` | Content editors paste image URLs from anywhere (Quill's image-by-URL is the sanctioned shape, so the landing and trip pages carry arbitrary https images), chat, profile, branding and lodging photos come from the CDN, PrimeFaces themes inline `data:` images, the photo viewer and the crop dialog show `blob:` previews, the analytics tag and PayPal's button use pixel images. Any https image is allowed; scripts and frames stay narrow, which is where the protection is. |
| `font-src` | `'self' data: https:` | The Freya theme ships its fonts as resources; `data:` for inlined icon fonts; any https host for the font files a trip stylesheet's `@import` resolves to (`fonts.gstatic.com` today). |
| `connect-src` | `'self' https://api.stripe.com https://*.google-analytics.com https://*.analytics.google.com https://www.googletagmanager.com {media}` | Ajax, the REST API and the chat long-poll are same-origin; Stripe.js talks to its API (Stripe's documented list); the analytics tag beacons to Google's collectors; the CDN in case a script fetches a library item. |
| `frame-src` | `'self' https:` | Content editors paste embeds from sites nobody knows in advance (YouTube or Vimeo players, Google Maps, a Google Form on the old microsite pages), and Stripe.js opens its Payment Element and 3-D Secure frames itself. A framed page runs in its own origin and cannot read or script ours, so any https site may be framed; the protection lives in `script-src` and `connect-src`, which stay narrow. |
| `object-src` | `'self' {media}` | `admin/mediaItem.xhtml` previews a PDF inline with an `<object>` pointing at the CDN (locally at a servlet). |
| `media-src` | `'self' blob: https:` | Pasted audio or video from any https site, the same reasoning as frames; `blob:` for the check-in camera stream. |
| `worker-src` | `'self'` | `sw.js` (Web Push). |
| `manifest-src` | `'self'` | `/manifest.jsf`. |
| `report-uri` / `report-to` | `/csp-report` / `csp-endpoint` | Both reporting mechanisms, same servlet. `Reporting-Endpoints: csp-endpoint="/csp-report"` accompanies the policy. |

Deliberately absent: `upgrade-insecure-requests` (the site has no http subresources of its own; the old microsite
pages link out over http, and a link is not a subresource) and `require-trusted-types-for` (PrimeFaces is nowhere
near it).

## Reading the reports

Browsers POST a JSON report for every violation of a report-only policy. `CspReportServlet` logs each as one
`WARN` line, `CSP violation report from <user agent>: <json>`, capped at 20 a minute per JVM so a misbehaving
extension or a scanner cannot bill CloudWatch by the byte (the cap drops reports; it never fails a request). In
production they are in the application log group (`/ecs/trip-manager`); the filter to search:

```
"CSP violation report"
```

What to look for in a report: `violated-directive` or `effective-directive` (which rule), `blocked-uri` (what was
refused; `inline` means an inline script or style, `eval` an eval), `document-uri` (the page) and `source-file`
plus `line-number` when the browser knows them. Three kinds of report, and what each means:

1. **A source the site really loads** (a `blocked-uri` on a host we recognise, from a `document-uri` of ours):
   the allowlist is incomplete. Add the source to `ContentSecurityPolicy` with a one-line reason, extend
   `ContentSecurityPolicyTest`, and if a webtest can reach the page, add it to `SecurityHeadersPwIT`'s sweep.
2. **Browser extension noise** (`blocked-uri` of `chrome-extension://`, `moz-extension://`, `safari-extension://`,
   or a `source-file` of one; also `about:blank`, `data` from an extension's injected image): ignore. Enforcing
   the policy blocks the extension's injection on our pages, which is the extension's problem, not ours.
3. **A user-pasted resource an editor added to a content section** (an https image is allowed; a pasted
   `<script>` or a non-https image is not): the policy is doing its job. Leave it.

The webtest sweeps (`SecurityHeadersPwIT.theBusiestPagesRaiseNoPolicyViolations` on the shared host and
`theOrgSiteAndMarketingHostsRaiseNoPolicyViolations` on an org site and the marketing host) are the first line: they
fail locally before a deploy on any violation on the pages they drive, so production reports should be the pages
they do not reach (the microsite pages with their embeds, a conference page with a pasted resource) and extension
noise. The policy is one string for every host and `'self'` is whatever origin served the page, so a new domain
or org subdomain pointed at the load balancer needs nothing here; only a second media CDN origin would.

## Moving to enforcing

1. Leave `report-only` in production through at least two weeks of ordinary use that includes a Stripe payment, a
   PayPal payment, a photo upload, a chat session and the admin pages.
2. Read every `CSP violation report` line in that window (above). Each is one of the three kinds; fix the first
   kind, and keep going until a week passes with only the second and third.
3. Set `cspMode` to `enforce` in `web.xml`, run the webtest suite, deploy.
4. Keep the report sink: an enforcing policy reports exactly like a report-only one, so the same log line is the
   signal that something a user needs is being blocked. Act on the first kind at once (it is now a broken page).

Tightening after that, in order of value: drop `'unsafe-eval'` and `'unsafe-inline'` from `script-src` by turning
on PrimeFaces' CSP mode (`primefaces.CSP=true`, nonce-based; it rewrites PrimeFaces' own inline handlers, but the
`jsft` scripts and the handful of inline `<script>` blocks in the templates need the nonce too, so this is a
project, not a flag), then narrow `img-src https:` if the content model ever stops accepting arbitrary image URLs.

## Rollback

Each step is a `web.xml` edit and a deploy; none needs a code change.

| Symptom | Change |
|---|---|
| A page is blocked by the policy after it was set to `enforce` | `cspMode` back to `report-only`. Nothing is blocked; reports keep flowing. |
| The policy itself is suspect (reports flood, the sink's cap is hit all day) | `cspMode=off`. No policy header, no `Reporting-Endpoints`; the three other headers stay. |
| An https problem after HSTS went out | Set `hstsMaxAgeSeconds` to `0`: a `max-age=0` header tells every browser that has seen the site to FORGET its HSTS state on the next https visit. Removing the header does not do that; browsers would keep the old max-age until it expired, which is why the first value is 300 seconds. Never set `includeSubDomains` while the max-age is 0. |
| A page needs to be framed by another origin | `frameOptions` to the needed value is not enough on its own: `frame-ancestors` in the policy says `'self'` and wins in every modern browser. Add the origin to `frame-ancestors` in `ContentSecurityPolicy` (a code change) and set `frameOptions` to match, or remove X-Frame-Options and let the policy carry it alone. |
| `nosniff` breaks a resource (a script or stylesheet served with the wrong type) | Fix the type, not the header: the resource is wrong for every browser that enforces nosniff by default on scripts anyway. If it must ship first, the filter has no knob for it by design; revert the filter's `X-Content-Type-Options` line. |

To undo the whole feature: remove the `SecurityHeaders` filter and `CspReport` servlet from `web.xml`, deploy.
HSTS state in browsers still expires on its own within the max-age that was last sent (300 seconds as shipped).
