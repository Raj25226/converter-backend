# Unimorpher

Unimorpher converts documents, images, audio, video, and archives. This repository contains the Java 21 / Spring Boot backend. The Next.js frontend lives in the sibling `../converter-frontend` repository.

Guests can convert without signing in. Accounts add email verification, Google sign-in, profile settings, password recovery, and conversion history. Administrators can inspect usage, search users, change roles, suspend accounts, and delete users with their associated history.

## Run the complete stack

Requires Docker Compose and both repositories checked out side by side:

```text
Github/
  converter-backend/
  converter-frontend/
```

```sh
cp .env.example .env
# Edit .env: set DB_PASSWORD and JWT_SECRET; configure SMTP and Google OAuth.
# Generate JWT_SECRET with: openssl rand -base64 64

docker compose up --build -d
```

Open **http://localhost:8088**. The gateway sends API requests directly to the backend and other requests to the frontend. Direct database, backend, and frontend ports are bound to localhost. The backend health endpoint is `/actuator/health` (available directly on localhost:8080); analytics endpoints require admin access.

Use your real HTTPS hostname for `FRONTEND_URL`, `BACKEND_URL`, and `CORS_ALLOWED_ORIGINS` before deploying. Terminate TLS at a controlled edge. The bundled gateway is HTTP for local use. Build the frontend again when changing its public site URL, Google client ID, or API proxy URL.

Configure Google OAuth with the same client ID on both sides and your frontend origin in Google's authorized JavaScript origins. SMTP delivers verification and password-reset emails. Those workflows cannot work without valid SMTP credentials. The frontend hides Google sign-in when its client ID is absent.

**Rotate the database, SMTP, and JWT credentials previously embedded in this repository before deployment.** Removing them from the current configuration does not remove them from Git history or revoke them.

## Features and limits

| Tool | Implementation and limits |
| --- | --- |
| PDF → Word | Extracts selectable text into DOCX; no OCR or layout reconstruction. |
| Word → PDF | DOCX paragraphs and tables render as text; legacy DOC uses LibreOffice. |
| PDF → Excel | Extracts text and heuristically separates table columns; not OCR. |
| Excel → PDF | XLS/XLSX values render as text; charts and print layouts are not preserved. |
| PowerPoint → PDF | PPTX slides render as images; legacy PPT uses LibreOffice. |
| JPG ↔ PNG | ImageIO conversion; transparent PNGs use a white JPEG background. |
| JPG → WebP | FFmpeg with the WebP encoder. |
| WebP → PNG | ImageIO WebP reader. |
| HEIC → JPG | FFmpeg, with ImageMagick/HEIC fallback. |
| MP4 → MP3, WAV → MP3 | FFmpeg audio encoding. |
| MP4 → GIF | FFmpeg; clips are limited to 30 seconds. |
| ZIP Extract | Reads and repackages safe entries into a downloadable ZIP; it does not expose individual extracted downloads. |
| File Compress | JPEG quality reduction, PNG/PDF re-encoding, or GZIP for other input types. A smaller output is not guaranteed. |

Defaults: 50MB uploads, two guest attempts/day, ten signed-in attempts/day, and 30-minute downloads. Quotas reset at midnight UTC. Attempts consume quota even when processing fails or the browser cancels. Cancelling the upload does not stop work already received by the server.

Daily usage is stored separately from conversion history and reserved atomically in PostgreSQL. Deleting history or expiring files does not reset usage. Guest identity is the normalized client IP; shared networks share guest quota. Auth and visit tracking also have per-minute request limits. Two conversions can run simultaneously per backend instance; additional requests receive 503. External conversion processes time out after two minutes.

Download URLs are unguessable bearer links: anyone with the link can download before expiry. Do not share sensitive links. Downloads use `no-store`. Expired output files are removed by a scheduled job, normally within one minute of expiry while the service is running. Conversion metadata (including filenames and client IP) is retained for approximately 30 days after expiry. Orphaned work files are swept hourly after expiry plus a ten-minute grace period.

PDFs have a 500-page limit. Images decoded with ImageIO are limited to 25 megapixels and 10,000 pixels per side. ZIPs permit at most 1,000 entries, 100MB per entry, and 500MB total uncompressed data. Unsafe archive paths are skipped.

## Database migrations

Flyway manages the schema; Hibernate validates it instead of changing it during startup.

- V1: original application tables.
- V2: independent quota ledger.
- V3: JWT revocation version. Changing or resetting a password invalidates existing tokens.

For a **new database**, migrations run automatically. For an **existing database created by the old Hibernate `update` setting**, back it up and compare its schema against V1 first. If compatible, perform a one-time deployment with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and `SPRING_FLYWAY_BASELINE_VERSION=1`, then remove those settings. This baselines the existing tables and applies V2 and V3. Do not silently baseline an unreviewed schema.

The first administrator must be provisioned by a database operator after creating and verifying an account. For example, execute a parameterized update of that account's `users.role` to `ADMIN`. There is no public bootstrap-admin endpoint. Role changes and account suspensions are checked against the database on each authenticated request.

## Local development and checks

```sh
# Backend: Java 21 or a compatible newer JDK; PostgreSQL required to run the app.
# Export configuration from .env through your development environment.
sh mvnw test
sh mvnw spring-boot:run

# Frontend: Node 22+
cd ../converter-frontend
cp .env.example .env.local
npm ci
npm run dev
npm run lint
npm run build
npm run typecheck
npm audit --omit=dev
```

For separate local frontend/backend processes, use `API_URL=http://localhost:8080`, `FRONTEND_URL=http://localhost:3000`, `BACKEND_URL=http://localhost:8080`, and `CORS_ALLOWED_ORIGINS=http://localhost:3000`.

Backend tests cover document and image outputs, malformed input, archive safety, compression round trips, migrations/startup, public health, authentication, revoked roles/tokens, and conversion downloads. H2 integration tests mock quota reservations because their SQL is PostgreSQL-specific.

To run the actual PostgreSQL quota concurrency/reset tests, point `QUOTA_TEST_DATABASE_URL`, `QUOTA_TEST_DATABASE_USER`, and `QUOTA_TEST_DATABASE_PASSWORD` at an isolated test database, then run `sh mvnw test`. These tests create and remove a uniquely named test schema. GitHub Actions provides PostgreSQL and runs these checks automatically. Without these variables, the two PostgreSQL tests are skipped.

## Deployment considerations

The Docker backend installs FFmpeg, ImageMagick with HEIC support, LibreOffice, and fonts, and runs as a non-root user. Docker images and media executables need to be smoke-tested with representative files before a release. OAuth/SMTP need a test using real configured providers.

The Compose network uses `172.30.0.0/24`, with the gateway at `.2`. The backend trusts forwarded addresses only from that gateway, which overwrites incoming forwarding headers. If changing the network or adding a load balancer, update the trusted-proxy setting and edge rules together. Do not expose arbitrary trusted forwarding headers to clients. Standalone requests use the socket client address by default.

File storage is local to the backend container. Keep a single backend instance unless output storage is shared; a database alone does not make download files available across replicas. Back up PostgreSQL, set container CPU/memory/disk limits, monitor disk space and health, and configure dependency scanning and an update process. Native parsers run in the application container; a separate isolated worker tier and malware scanning are further hardening options for hostile public uploads.

Unique-visitor analytics are approximate: browser sessions are deduplicated in a bounded per-instance cache that resets daily, at capacity, or on restart. Conversion counters are persisted.

The frontend's production dependency audit is clean at implementation time. Development-only Tailwind/ESLint dependencies still have reported advisories; upgrading their major versions requires a separate tooling migration. JWTs currently live in browser localStorage; protect the frontend from XSS and plan an HttpOnly cookie session migration if the deployment requires it.

No paid plan, billing system, unlimited quota, OCR, or perfect Office/PDF layout preservation is implemented. The frontend no longer advertises those unavailable capabilities.
