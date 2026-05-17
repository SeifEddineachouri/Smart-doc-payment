# SmartDoc Payment Service (Spring Boot)

This service is the Spring Boot version of the SmartDoc payment microservice. It creates checkout sessions, verifies provider webhooks, and manages entitlements so users must pay before they can use the application.

## What it does
- hosted checkout session creation
- webhook verification with Stripe-style signatures
- entitlement activation/deactivation
- refund endpoint protected by an internal token
- in-memory repository for development and tests

## Run locally

```powershell
cd C:\Users\seifa\Documents\smartdoc\payment-service-spring
..\mvnw.cmd -f pom.xml spring-boot:run
```

If you have Maven installed locally, this also works:

```powershell
mvn spring-boot:run
```

## Run tests

```powershell
cd C:\Users\seifa\Documents\smartdoc\payment-service-spring
..\mvnw.cmd -f pom.xml test
```

## API
- `POST /api/v1/payments/checkout-session`
- `GET /api/v1/payments/entitlement/{userId}`
- `GET /api/v1/payments/status/{userId}`
- `POST /api/v1/payments/webhooks/stripe`
- `POST /api/v1/payments/refund`

## Environment variables
- `PAYMENT_PROVIDER`
- `PAYMENT_CHECKOUT_BASE_URL`
- `PAYMENT_STRIPE_WEBHOOK_SECRET`
- `PAYMENT_INTERNAL_TOKEN`
- `PAYMENT_WEBHOOK_TOLERANCE_SECONDS`
- `PAYMENT_DEFAULT_PLAN_ID`
- `PAYMENT_DEFAULT_PLAN_NAME`
- `PAYMENT_DEFAULT_PLAN_PRICE_CENTS`
- `PAYMENT_DEFAULT_PLAN_CURRENCY`
- `PAYMENT_DEFAULT_PLAN_INTERVAL`
- `PAYMENT_DEFAULT_PLAN_ACTIVE`

## Next step
Connect this service to the main SmartDoc app and swap the in-memory repository for a database-backed implementation.

