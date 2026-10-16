package com.converter.service;

import com.converter.conversion.*;
import com.converter.dto.*;
import com.converter.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Durable, bounded jobs. Uploads survive HTTP disconnects; quota is reserved once at acceptance. */
@Service
@Slf4j
public class ConversionJobService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversionService conversions;
    private final ConversionSettings conversionSettings;
    private final JobSettings settings;
    private final ObjectMapper json;
    private final Path uploads;
    private final ThreadPoolExecutor workers;
    private final Semaphore workerSlots;
    private final Map<String, Running> active = new ConcurrentHashMap<>();
    private final String instance = UUID.randomUUID().toString().replace("-", "");
    private volatile boolean stopping;
    private static final class Running {
        private final Thread thread;
        private boolean finished;
        Running(Thread thread) { this.thread = thread; }
        synchronized void cancel() { if (!finished) thread.interrupt(); }
        synchronized void finish() { finished = true; Thread.interrupted(); }
    }
    @Value("${file.max-size-mb}") private int maxFileMb;
    private record Job(String id, String owner, String request, Long user, String session, String ip, String tool,
                       String name, String status, String worker, String result, String error, Instant created, Instant updated) { }
    private static final RowMapper<Job> ROW = (rs, row) -> new Job(rs.getString("id"), rs.getString("owner_key"), rs.getString("request_id"),
            rs.getObject("user_id", Long.class), rs.getString("session_id"), rs.getString("ip_address"), rs.getString("tool"),
            rs.getString("original_name"), rs.getString("status"), rs.getString("worker_id"), rs.getString("result_json"),
            rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    public ConversionJobService(JdbcTemplate jdbc, PlatformTransactionManager manager, ConversionService conversions,
                                ConversionSettings conversionSettings, JobSettings settings, ObjectMapper json,
                                @Qualifier("uploadPath") Path uploadPath) throws IOException {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.conversions = conversions;
        this.conversionSettings = conversionSettings; this.settings = settings; this.json = json;
        uploads = uploadPath.resolve("jobs"); Files.createDirectories(uploads);
        int count = conversionSettings.getMaxConcurrent(); workerSlots = new Semaphore(count);
        workers = new ThreadPoolExecutor(count, count, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().name("conversion-job-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    public ConversionJobResponse submit(String tool, MultipartFile file, String requestId, Long userId, String sessionId, String ip) throws IOException {
        if (!settings.isEnabled() || stopping) throw new AppException("Background conversions are unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
        conversions.converterFor(tool); validateRequestId(requestId);
        if (file == null || file.isEmpty()) throw new ConversionException("Upload a non-empty file to convert.");
        if (file.getSize() > maxFileMb * 1024L * 1024) throw new AppException("File exceeds the upload size limit.", HttpStatus.PAYLOAD_TOO_LARGE);
        String owner = owner(userId, ip), id = UUID.randomUUID().toString().replace("-", "");
        Job accepted = transactions.execute(tx -> {
            // Serialize admission across instances. No filesystem IO runs while holding this lock.
            jdbc.queryForObject("SELECT id FROM conversion_job_admission WHERE id = 1 FOR UPDATE", Integer.class);
            var existing = findRequest(owner, requestId);
            if (existing != null) {
                if (!existing.tool.equals(tool)) throw new AppException("This request ID was already used for another tool.", HttpStatus.CONFLICT);
                return existing;
            }
            long pending = jdbc.queryForObject("SELECT COUNT(*) FROM conversion_jobs WHERE status IN ('uploading','queued','running')", Long.class);
            if (pending >= settings.getMaxPending()) throw new AppException("The conversion queue is full. Try again shortly.", HttpStatus.SERVICE_UNAVAILABLE);
            conversions.reserveJobQuota(userId, ip);
            Instant now = Instant.now();
            jdbc.update("INSERT INTO conversion_jobs (id, owner_key, request_id, user_id, session_id, ip_address, tool, original_name, status, created_at, updated_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, owner, requestId, userId, sessionId == null ? null : sessionId.substring(0, Math.min(255, sessionId.length())), ip, tool,
                    UploadValidation.displayName(file), "uploading", time(now), time(now), time(now.plusSeconds(settings.getRetentionMinutes() * 60L)));
            return find(id);
        });
        if (!accepted.id.equals(id)) return response(accepted);
        Path path = input(id);
        try {
            file.transferTo(path);
            transactions.executeWithoutResult(tx -> {
                int changed = jdbc.update("UPDATE conversion_jobs SET status = 'queued', updated_at = ? WHERE id = ? AND status = 'uploading'", time(Instant.now()), id);
                if (changed != 1) throw new AppException("The upload was cancelled or expired.", HttpStatus.CONFLICT);
            });
        } catch (Exception failure) {
            removeInput(id);
            jdbc.update("UPDATE conversion_jobs SET status = 'failed', error_message = ?, updated_at = ? WHERE id = ? AND status = 'uploading'",
                    failure instanceof AppException ? failure.getMessage() : "The upload could not be stored. Try again.", time(Instant.now()), id);
            if (failure instanceof AppException app) throw app;
            throw new ConversionException("The upload could not be stored. Try again.");
        }
        return response(find(id));
    }
    public ConversionJobResponse get(String id, Long userId) { return response(authorized(id, userId)); }
    public ConversionJobResponse byRequest(String requestId, Long userId, String ip) {
        validateRequestId(requestId); Job job = findRequest(owner(userId, ip), requestId);
        if (job == null) throw missing(); return response(job);
    }
    public List<ConversionJobResponse> recent(Long userId, String tool) {
        if (userId == null) return List.of();
        conversions.converterFor(tool);
        return jdbc.query("SELECT * FROM conversion_jobs WHERE user_id = ? AND tool = ? ORDER BY CASE WHEN status IN ('uploading','queued','running') THEN 0 ELSE 1 END, created_at DESC, id DESC LIMIT 20", ROW, userId, tool)
                .stream().map(this::response).toList();
    }
    public ConversionJobResponse cancel(String id, Long userId) {
        Job previous = authorized(id, userId);
        int changed = jdbc.update("UPDATE conversion_jobs SET status = 'cancelled', error_message = 'Conversion cancelled.', updated_at = ? WHERE id = ? AND status IN ('uploading','queued','running')", time(Instant.now()), id);
        if (changed > 0) { Running running = active.get(id); if (running != null) running.cancel();
            else if (!previous.status.equals("uploading")) removeInput(id); }
        return response(find(id));
    }
    @Scheduled(fixedDelayString = "${jobs.poll-interval-ms:1000}")
    public void dispatch() {
        if (!settings.isEnabled() || stopping) return;
        for (int dispatched = 0; dispatched < conversionSettings.getMaxConcurrent() && workerSlots.tryAcquire(); dispatched++) {
            Job job;
            try {
                job = transactions.execute(tx -> {
                    var candidates = jdbc.query("SELECT * FROM conversion_jobs WHERE status = 'queued' ORDER BY created_at, id LIMIT 1", ROW);
                    if (candidates.isEmpty()) return null;
                    Job candidate = candidates.getFirst();
                    return jdbc.update("UPDATE conversion_jobs SET status = 'running', worker_id = ?, updated_at = ? WHERE id = ? AND status = 'queued'",
                            instance, time(Instant.now()), candidate.id) == 1 ? find(candidate.id) : null;
                });
            } catch (RuntimeException failure) { workerSlots.release(); log.warn("Job dispatch failed", failure); return; }
            if (job == null) { workerSlots.release(); return; }
            try { workers.execute(() -> run(job)); }
            catch (RejectedExecutionException failure) { try { requeue(job); } finally { workerSlots.release(); } return; }
        }
    }
    private void run(Job job) {
        Running running = new Running(Thread.currentThread()); active.put(job.id, running); boolean remove = true; ConversionResponse output = null;
        try {
            Job current = find(job.id);
            if (current == null || !current.status.equals("running")) return;
            var result = conversions.runReservedJob(new StoredMultipartFile(input(job.id), job.name), job.user, job.session, job.ip, job.tool);
            output = result; Thread.interrupted();
            Instant retainedUntil = Instant.now().plusSeconds(settings.getRetentionMinutes() * 60L);
            if (result.getExpiresAt().isAfter(retainedUntil)) retainedUntil = result.getExpiresAt();
            if (jdbc.update("UPDATE conversion_jobs SET status = 'succeeded', result_json = ?, updated_at = ?, expires_at = ? WHERE id = ? AND status = 'running' AND worker_id = ?",
                    json.writeValueAsString(result), time(Instant.now()), time(retainedUntil), job.id, instance) != 1) conversions.discardJobResult(result);
            output = null;
        } catch (ConversionBusyException busy) {
            remove = !requeue(job);
        } catch (Exception failure) {
            Thread.interrupted();
            if (output != null) conversions.discardJobResult(output);
            String message = failure instanceof AppException ? failure.getMessage() : "The conversion failed. Try again with a valid file.";
            log.warn("Background conversion {} failed", job.id, failure);
            jdbc.update("UPDATE conversion_jobs SET status = 'failed', error_message = ?, updated_at = ? WHERE id = ? AND status = 'running' AND worker_id = ?",
                    message, time(Instant.now()), job.id, instance);
        } finally {
            running.finish(); active.remove(job.id, running); if (remove) removeInput(job.id); workerSlots.release();
        }
    }
    private boolean requeue(Job job) {
        return jdbc.update("UPDATE conversion_jobs SET status = 'queued', worker_id = NULL, updated_at = ? WHERE id = ? AND status = 'running' AND worker_id = ?", time(Instant.now()), job.id, instance) == 1;
    }
    @Scheduled(fixedDelayString = "${jobs.maintenance-interval-ms:10000}")
    public void maintain() {
        Instant now = Instant.now();
        for (var entry : active.entrySet()) {
            int renewed = jdbc.update("UPDATE conversion_jobs SET updated_at = ? WHERE id = ? AND worker_id = ? AND status = 'running'", time(now), entry.getKey(), instance);
            if (renewed == 0) entry.getValue().cancel();
        }
        jdbc.update("UPDATE conversion_jobs SET status = 'failed', error_message = 'Processing was interrupted. Submit the file again.', updated_at = ? WHERE (status = 'uploading' AND updated_at < ?) OR (status = 'running' AND updated_at < ?) OR (status = 'queued' AND created_at < ?)",
                time(now), time(now.minusSeconds(settings.getUploadTimeoutSeconds())), time(now.minusSeconds(conversionSettings.getTimeoutSeconds() + 60L)), time(now.minusSeconds(settings.getQueueTimeoutSeconds())));
        try (var staged = Files.newDirectoryStream(uploads, "*.upload")) {
            for (Path path : staged) {
                String name = path.getFileName().toString();
                if (!name.matches("[a-f0-9]{32}\\.upload")) continue;
                String id = name.substring(0, 32);
                if (active.containsKey(id)) continue;
                Job job = find(id);
                if (job == null || Set.of("succeeded", "failed", "cancelled").contains(job.status)) removeInput(id);
            }
        } catch (IOException failure) { log.warn("Could not clean job uploads", failure); }
        jdbc.update("DELETE FROM conversion_jobs WHERE id IN (SELECT id FROM conversion_jobs WHERE expires_at < ? AND status IN ('succeeded','failed','cancelled') ORDER BY expires_at LIMIT 100)", time(now));
    }
    private Job authorized(String id, Long userId) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw missing(); Job job = find(id);
        if (job == null || (job.user != null && !job.user.equals(userId))) throw missing();
        return job; // Guest IDs are unguessable bearer capabilities, like public download IDs.
    }
    private ConversionJobResponse response(Job job) {
        if (job == null) throw missing();
        try {
            ConversionResponse result = job.result == null ? null : json.readValue(job.result, ConversionResponse.class);
            String status = job.status, error = job.error;
            if (result != null && (!result.getExpiresAt().isAfter(Instant.now()) || !conversions.isJobResultAvailable(result))) { status = "expired"; result = null; error = "This download has expired or was removed."; }
            return new ConversionJobResponse(job.id, job.request, status, job.tool, job.name, job.created, job.updated, result, error);
        } catch (IOException failure) { throw new IllegalStateException("Invalid persisted conversion result", failure); }
    }
    private Job find(String id) { return jdbc.query("SELECT * FROM conversion_jobs WHERE id = ?", ROW, id).stream().findFirst().orElse(null); }
    private Job findRequest(String owner, String request) { return jdbc.query("SELECT * FROM conversion_jobs WHERE owner_key = ? AND request_id = ?", ROW, owner, request).stream().findFirst().orElse(null); }
    private Path input(String id) { return uploads.resolve(id + ".upload"); }
    private void removeInput(String id) { try { Files.deleteIfExists(input(id)); } catch (IOException failure) { log.warn("Could not remove staged job {}", id, failure); } }
    private static Timestamp time(Instant instant) { return Timestamp.from(instant); }
    private static void validateRequestId(String id) { if (id == null || !id.matches("[a-zA-Z0-9-]{16,64}")) throw new AppException("A valid request ID is required.", HttpStatus.BAD_REQUEST); }
    private static AppException missing() { return new AppException("Conversion job not found.", HttpStatus.NOT_FOUND); }
    private static String owner(Long userId, String ip) {
        if (userId != null) return "user:" + userId;
        try { return "guest:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(ip).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    @PreDestroy public void close() {
        stopping = true; workers.shutdownNow();
        try { workers.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }
}
