---
paths:
  - "trip/src/main/java/org/paulsens/trip/pay/**"
  - "trip/src/test/java/org/paulsens/trip/pay/**"
---

# Payments and money math (`pay/`)

Read `docs/payments.md` before touching processors, `Payment` or org money rules; lodging money fields follow
`docs/lodging.md`, "Price vs cost".

- PayPal SDK errors: `ErrorException.getMessage()` is the same fixed 422 reason for every error; read
  `getDetails()[].getIssue()` (falling back to the raw body), as `PayPalProcessor.isAlreadyCaptured` does. A test
  mock must use that real shape: an issue code placed in `getMessage()` passes the test and never matches in
  production.
