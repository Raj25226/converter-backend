# Unimorpher

Unimorpher converts documents, images, audio, video, and archives. This repository contains the Java 25 / Spring Boot 4.1 backend. The Next.js frontend lives in the sibling `../converter-frontend` repository.

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
| PDF → Word | LibreOffice PDF layout import preserves editable positioned text, font sizes/styles/colors, images, graphics, and page dimensions. Page anchors are normalized and DOCX package validation is mandatory. Optional deep verification round-trips the DOCX to PDF, checking per-page text and geometry; text differences return warnings rather than rejecting the file. Text boxes are used rather than recovering the original Word paragraph/table structure. Scans remain images and return an OCR warning. |
| Word → PDF | DOC/DOCX use LibreOffice Writer to render formatting, tables, images, headers/footers, links, and pagination. Fonts must be installed on the server; unsupported Office features can differ from Microsoft Word. |
| PDF → Excel | Glyph coordinates infer columns, with rich text fonts, sizes, bold/italic, and colors. Every displayed value remains text, preserving leading zeros, long IDs, currency, and formula-looking strings. Original formulas, merged cells, borders, graphics, and spreadsheet structure cannot be recovered; no OCR. |
| Excel → PDF | XLS/XLSX use LibreOffice Calc with the original workbook, print areas, page settings, visible sheets, displayed values, charts, and images. The backend does not rewrite or force-fit the workbook. |
| PowerPoint → PDF | PPT/PPTX use LibreOffice Impress for static slide layouts, text, shapes, and pictures. Animations, transitions, notes, and embedded audio/video are not retained. |
| JPG → PNG | ImageMagick applies EXIF orientation and converts colors to sRGB before removing metadata. PNG encoding adds no lossy compression, but the source JPEG has already been compressed. |
| PNG → JPG | ImageMagick uses configurable JPEG quality (92 by default) and a white background for transparency. APNG inputs are rejected rather than silently discarding animation. |
| JPG → WebP | ImageMagick uses configurable lossy WebP quality (90 by default). |
| WebP → PNG | ImageMagick preserves the still image and alpha channel. Animated WebP is rejected rather than returning only its first frame. |
| HEIC → JPG | ImageMagick/libheif converts the primary HEIC/HEIF image. Auxiliary images, depth maps, and HDR data are not retained. |
| MP4 → MP3 | FFmpeg extracts the first audio track; supported source sample rates and mono/stereo are retained, and supported text tags are copied. Surround audio is downmixed with a warning. Video, additional tracks, artwork, and chapters are not retained. |
| WAV → MP3 | Same audio policy; MP3 encoding is lossy. Unsupported source sample rates use the configured fallback (44.1kHz by default). Encoded codec, sample rate, channels, and known source duration are verified. |
| MP4 → GIF | FFmpeg converts the first video track with a generated palette, preserving display aspect ratio (including non-square pixels). Defaults: first 30 seconds, 10fps, at most 480 pixels per dimension, infinite looping, and no audio. |
| ZIP Extract | Repackages safe entries, checking expansion limits, declared sizes, and CRCs. Preserves contents, directories, modification times, comments, and safe Unix permissions. Unsafe paths/symlinks/device entries are omitted with warnings; duplicate/conflicting paths and damaged entries are rejected. |
| File Compress | JPEG quality reduction, PNG/PDF re-encoding, or lossless GZIP for other input types. JPEG/PNG/PDF return the original unchanged if re-encoding does not shrink it. Signed PDFs are rejected to avoid invalidating signatures. |

Successful API responses include a `warnings` array when the conversion changes or cannot recover features. Clients should display these messages with the result. Existing success fields and download routes are unchanged. Warnings describe actual output policies; they are not a claim of pixel-perfect conversion.

PDF-to-Word uses the maintained LibreOffice [PDF import and DOCX export filters](https://help.libreoffice.org/latest/en-US/text/shared/guide/convertfilters.html). PDF stores painted page content rather than original Office structure, so native tables, semantic headers/footers, reading order in complicated layouts, annotations, forms, and font availability still require representative document testing. When enabled, deep verification reports selectable-character differences as warnings and rejects changed page geometry; it cannot detect every possible visual difference. Install any required licensed source fonts on the server and in the Word viewer.

All image conversions use sRGB color management. `IMAGE_SRGB_PROFILE` can select an ICC profile; otherwise the standard Ubuntu or macOS sRGB profile is discovered automatically. Without an installed profile, an explicit color-management warning is returned. Docker and CI install `icc-profiles-free`. This uses ImageMagick's [ICC profile conversion](https://imagemagick.org/command-line-options/#profile) before stripping metadata.

Defaults: 50MB uploads, two guest attempts/day, ten signed-in attempts/day, and 30-minute downloads. Quotas reset at midnight UTC. Attempts consume quota even when processing fails or the browser cancels. Cancelling an upload can leave a job already accepted by the server; recover it on the same tool page. Accepted background jobs have an explicit cancellation endpoint. Attempts, including cancelled accepted jobs, still consume quota.

Daily usage is stored separately from conversion history and reserved atomically in PostgreSQL. Deleting history or expiring files does not reset usage. Guest identity is the normalized client IP; shared networks share guest quota. Auth and visit tracking also have per-minute request limits. Two conversions can run simultaneously per backend instance. Background submissions wait in a bounded queue (20 accepted/pending jobs by default); a full queue returns 503. Legacy synchronous submissions receive 503 when workers are busy. External conversion processes time out after two minutes.

Download URLs are unguessable bearer links: anyone with the link can download before expiry. Do not share sensitive links. Downloads use `no-store`. Expired output files are removed by a scheduled job, normally within one minute of expiry while the service is running. Conversion metadata (including filenames and client IP) is retained for approximately 30 days after expiry. Orphaned work files are swept hourly after expiry plus a ten-minute grace period.

PDFs have a 500-page limit and a 500,000-character extraction budget. PDF-to-Excel also enforces the spreadsheet cell limit and a configurable 1,024-style budget. Images decoded with ImageIO are limited to 25 megapixels and 10,000 pixels per side. ZIPs permit at most 1,000 entries, 100MB per entry, and 500MB total uncompressed data. Unsafe archive paths are skipped and reported. Password-protected inputs, macro-enabled OOXML documents, and externally linked Office resources are rejected.

## Background conversion and history APIs

Deploy the backend and its migrations before the new frontend. The frontend submits multipart uploads to `POST /api/jobs/{tool}` with `file` and a random UUID `requestId`. All 15 existing tool IDs are supported. The server returns HTTP 202 with `jobId`, `requestId`, and `status`; conversion runs outside the upload request. Existing `/api/convert/{tool}` synchronous routes remain available for older clients.

- `GET /api/jobs/{jobId}` returns `uploading`, `queued`, `running`, `succeeded`, `failed`, `cancelled`, or `expired`. Successful jobs include the original conversion response under `result`, including download URL and warnings.
- `DELETE /api/jobs/{jobId}` cancels a queued/running job. Running workers are interrupted, native subprocesses are stopped, and late results are discarded. Other worker instances observe cancellation during heartbeat maintenance.
- `GET /api/jobs/request/{requestId}` recovers a submission after its HTTP acknowledgement was lost. Retrying the same request ID for the same owner returns the existing job and does not reserve another quota attempt.
- `GET /api/jobs?tool=pdf-to-word` returns up to 20 active/recent jobs for a signed-in user. Signed-in job reads and cancellation require the owning account. Guest IDs are bearer capabilities, like guest download links.

The browser retains the current job/request ID per account and tool. Once the upload is accepted, leaving or refreshing the page does not cancel the conversion. Return to the tool to resume polling or retrieve the result. Polling pauses in hidden tabs and backs off on connection errors. Queue/running states are reported without fabricated processing percentages.

Staged uploads are stored under the upload directory and survive process restarts. Queued jobs can be claimed by a new worker. Running jobs whose heartbeat expires are marked interrupted rather than silently repeated. Job metadata is retained for 60 minutes by default, and successful jobs are retained at least until their download expires. Source uploads are removed after completion, failure, or cancellation. Multiple backend instances require shared **upload and output** storage as well as PostgreSQL.

`CONVERSION_JOBS_MAX_PENDING` bounds admission; `CONVERSION_MAX_CONCURRENT` bounds running conversions across background and synchronous paths. `CONVERSION_JOBS_QUEUE_TIMEOUT_SECONDS` bounds waiting time. Quota is reserved atomically with job admission before staging; failed/cancelled accepted attempts still count. Keep `CONVERSION_JOBS_ENABLED=true` for the new frontend.

`GET /api/user/history?page=0&size=20` returns one history slice with `page`, `size`, `hasNext`, and `hasPrevious`. Page size is limited to 100. Results are sorted by creation time and ID to handle timestamp ties without duplicates between unchanged pages. No full-history count query is needed. The dashboard loads only the current page and refreshes only while visible.

PDF verification defaults to `CONVERSION_PDF_VERIFICATION=structural`, avoiding the second LibreOffice render while retaining input limits, package validation, and page-anchor normalization. Set it to `deep` for round-trip text/page verification. Expired-file cleanup uses `FILE_CLEANUP_BATCH_SIZE` and `FILE_CLEANUP_MAX_BATCHES`; filesystem deletion runs outside database transactions and unsuccessful deletions are retried.

## Database migrations

Flyway manages the schema; Hibernate validates it instead of changing it during startup.

- V1: original application tables.
- V2: independent quota ledger.
- V3: JWT revocation version. Changing or resetting a password invalidates existing tokens.
- V4: composite index for stable, paginated user history.
- V5: durable background jobs and admission locking.

For a **new database**, migrations run automatically. For an **existing database created by the old Hibernate `update` setting**, back it up and compare its schema against V1 first. If compatible, perform a one-time deployment with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and `SPRING_FLYWAY_BASELINE_VERSION=1`, then remove those settings. This baselines the existing tables and applies V2 and V3. Do not silently baseline an unreviewed schema.

The first administrator must be provisioned by a database operator after creating and verifying an account. For example, execute a parameterized update of that account's `users.role` to `ADMIN`. There is no public bootstrap-admin endpoint. Role changes and account suspensions are checked against the database on each authenticated request.

## Local development and checks

```sh
# Backend: Java 25 or a compatible newer JDK; PostgreSQL required to run the app.
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

Backend tests exercise all 15 routes using real native tools. Fidelity regressions cover PDF colors/positions/images/page sizes, editable text boxes, scans, rich text spreadsheets, print areas, charts, slide pictures, EXIF orientation, CMYK colors, animation rejection, audio tags/channels/sample rates, GIF display aspect ratio, archive checksums/metadata, and signature-safe compression. Other tests cover malformed input, archive safety, compression round trips, migrations/startup, public health, authentication, revoked roles/tokens, and conversion downloads. H2 integration tests mock quota reservations because their SQL is PostgreSQL-specific.

To run the actual PostgreSQL quota concurrency/reset tests, point `QUOTA_TEST_DATABASE_URL`, `QUOTA_TEST_DATABASE_USER`, and `QUOTA_TEST_DATABASE_PASSWORD` at an isolated test database, then run `sh mvnw test`. These tests create and remove a uniquely named test schema. GitHub Actions provides PostgreSQL and runs these checks automatically. Without these variables, the two PostgreSQL tests are skipped.

## Deployment considerations

The backend builds and runs on Java 25 with Spring Boot 4.1.1. Use a Java 25 JDK
for Maven and configure the IDE's project SDK accordingly. Docker and CI also use
Java 25. Application JSON uses Jackson 3; JJWT retains a separately patched
Jackson 2 runtime dependency. Tomcat, both Jackson BOMs, Logback, and PostgreSQL
JDBC have explicit security overrides newer than Boot's managed baseline. ASM,
pulled in by JSONPath's test dependencies, is pinned through dependency management.

To check resolved application and test dependencies against OSV:

```bash
sh mvnw dependency:list -DoutputFile=target/dependencies-audit.txt
python3 scripts/audit-dependencies.py target/dependencies-audit.txt
```

The audit requires network access and sends only public package names and
versions. It exits unsuccessfully on advisory matches or an incomplete audit.
Results describe advisories known to OSV at scan time; other scanners may report
additional findings. Also review IDE/dependency scanner findings and scan container
OS packages separately as part of release verification.

The Docker backend installs FFmpeg, ImageMagick with HEIC support, LibreOffice Writer/Calc/Impress/Draw (including PDF import), Noto/Liberation fonts, and an sRGB ICC profile, and runs as a non-root user. Compose defaults to a 2GiB backend memory limit, 256 processes, and two CPUs; adjust `BACKEND_MEMORY_LIMIT`, the Compose CPU setting, and conversion concurrency together for your workload. Docker images and media executables need to be smoke-tested with representative files before a release. OAuth/SMTP need a test using real configured providers.

The Compose network uses `172.30.0.0/24`, with the gateway at `.2`. The backend trusts forwarded addresses only from that gateway, which overwrites incoming forwarding headers. If changing the network or adding a load balancer, update the trusted-proxy setting and edge rules together. Do not expose arbitrary trusted forwarding headers to clients. Standalone requests use the socket client address by default.

File storage is local to the backend container. Keep a single backend instance unless output storage is shared; a database alone does not make download files available across replicas. Back up PostgreSQL, set container CPU/memory/disk limits, monitor disk space and health, and configure dependency scanning and an update process. Native parsers run in the application container; a separate isolated worker tier and malware scanning are further hardening options for hostile public uploads.

Unique-visitor analytics are approximate: browser sessions are deduplicated in a bounded per-instance cache that resets daily, at capacity, or on restart. Conversion counters are persisted.

The frontend's production dependency audit is clean at implementation time. Development-only Tailwind/ESLint dependencies still have reported advisories; upgrading their major versions requires a separate tooling migration. JWTs currently live in browser localStorage; protect the frontend from XSS and plan an HttpOnly cookie session migration if the deployment requires it.

No paid plan, billing system, unlimited quota, OCR, or perfect Office/PDF layout preservation is implemented. The frontend no longer advertises those unavailable capabilities.


## Application configuration and local development

`src/main/resources/application.yml` is the source of application defaults. Every
operational setting has an environment override (`${ENV_NAME:default}`); Java
consumers do not supply fallback values or read environment variables directly.
Spring properties and command-line arguments can also override these values.

Start with local settings using:

```sh
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

`application-dev.yml` uses PostgreSQL at `localhost:5432/unimorpher` with
`admin/admin`, SMTP at `localhost:1025` without authentication/TLS, and storage
under `./var`. Start those services locally or override their settings. The dev
JWT key is exclusively for local use; other profiles require a `JWT_SECRET` of
at least 64 bytes. Google login requires `GOOGLE_CLIENT_ID`.

Native conversion tools must be installed, including LibreOffice PDF import support and an sRGB ICC profile. Set `LIBREOFFICE_BINARY`,
`IMAGEMAGICK_BINARY`, `FFMPEG_BINARY`, and `FFPROBE_BINARY` as needed. For macOS
LibreOffice, use `LIBREOFFICE_BINARY=/Applications/LibreOffice.app/Contents/MacOS/soffice`.
The dev profile disables native health probes by default; conversion routes still
require their respective tools.

Rate limits, request windows, token lifetimes, cleanup intervals, retention,
conversion budgets, image quality, media output parameters, pool sizes, CORS,
and log levels are configurable in the base YAML. Set `MAIL_FROM` to the sender
address accepted by your SMTP provider. Verification/reset email text uses the
configured token lifetime.

HTTP outcome logs include method, route template, status, duration, and a generated
request ID returned in `X-Request-ID`. Query strings, request bodies, and auth
tokens are omitted. `LOGGING_LEVEL_COM_CONVERTER` defaults to `INFO` (`DEBUG` in
dev); `LOGGING_PATTERN_CONSOLE` can customize the pattern.

## Feature flags

Migration V6 adds database overrides and an append-only change audit. V7 seeds
all 28 flags: login, signup, Google OAuth, email verification, password reset,
password changes, profile editing, and history are disabled; everything else is
enabled. Existing database choices are preserved. Seeded rows have no admin actor
(`updated_by` is null). These database values take precedence over environment
defaults in database mode. The administrator
page exposes switches for login, signup, Google OAuth, verification, password
recovery, profile editing, password changes, history, analytics, downloads,
background submissions, conversion availability, and each of the 15 converters.

`FEATURE_FLAGS_SOURCE=database` (default) lets authenticated administrators change
flags immediately through `/api/admin/features`. A stored database override wins
over its environment default. Reset deletes that override and uses the current
environment default again. Defaults are listed in `.env.example` and the dev YAML.
`FEATURE_FLAGS_SOURCE=environment` ignores database overrides and rejects flag
writes; change the environment and restart in that mode. Compose forwards these
settings. Only the `dev` profile loads `application-dev.yml`.

`GET /api/features` returns public booleans with `Cache-Control: no-store`, without
admin IDs or audit details. Browsers refresh on focus and every 15 seconds while
visible. Backend checks are authoritative on every new request, so developer tools,
modified JavaScript, and direct API requests cannot enable a disabled feature.
Management endpoints require a validated admin JWT; change requests cannot supply
the actor ID. Changes record the server-derived admin ID and timestamp in
`feature_flag_audit` (a null enabled value records a reset). Flags contain no secrets.

Disabled actions return HTTP 503 before quota is reserved. Switching off a converter
also prevents queued jobs for that converter from starting; running conversions may
finish. Disabling only background submissions lets accepted jobs finish. Job status,
cancellation, history deletion, account inspection, and admin flag management remain
available to authorized callers. Disabling downloads blocks existing download links.
Google sign-in also requires login; creating a new Google account additionally
requires signup. Login changes do not revoke existing sessions. Disabling email
verification does not mark accounts verified or bypass verification requirements.

Disabling login blocks new admin sign-ins too. If an admin session is lost, recover
by restarting with `FEATURE_FLAGS_SOURCE=environment` and `FEATURE_LOGIN=true`,
sign in, then restore database mode and reset the login override. Feature flags
control availability and never replace authentication, ownership, quotas, or roles.
Deploy the backend (including V6 and V7) before the frontend.
