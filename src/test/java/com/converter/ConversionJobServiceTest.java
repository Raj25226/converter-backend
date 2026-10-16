package com.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.converter.conversion.*;
import com.converter.dto.ConversionResponse;
import com.converter.exception.ConversionBusyException;
import com.converter.service.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ConversionJobServiceTest {
    @TempDir Path directory;

    @Test
    void cancellingRunningJobInterruptsWorkerAndDiscardsLateOutput() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:jobs" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__conversion_jobs.sql"))
                .execute(source);
        var conversions = mock(ConversionService.class);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var result =
                ConversionResponse.success(
                        "/api/download/01234567890123456789012345678901",
                        "late.gz",
                        Instant.now().plusSeconds(100),
                        5L);
        when(conversions.runReservedJob(
                        any(), isNull(), isNull(), anyString(), eq("file-compress")))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException expected) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            return result;
                        });
        var json = new ObjectMapper();
        var jobs =
                new ConversionJobService(
                        jdbc,
                        new DataSourceTransactionManager(source),
                        conversions,
                        TestSettings.settings(),
                        new JobSettings(),
                        json,
                        directory);
        ReflectionTestUtils.setField(jobs, "maxFileMb", 50);
        try {
            var accepted =
                    jobs.submit(
                            "file-compress",
                            new MockMultipartFile(
                                    "file", "late.txt", "text/plain", "data".getBytes()),
                            UUID.randomUUID().toString(),
                            null,
                            null,
                            "127.0.0.1");
            jobs.dispatch();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals("cancelled", jobs.cancel(accepted.jobId(), null).status());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            verify(conversions, timeout(3000)).discardJobResult(result);
            assertEquals("cancelled", jobs.get(accepted.jobId(), null).status());
            assertNull(jobs.get(accepted.jobId(), null).result());
            verify(conversions, times(1)).reserveJobQuota(null, "127.0.0.1");
        } finally {
            jobs.close();
        }
    }

    @Test
    void busySynchronousWorkersDoNotCauseUnboundedDispatchRetries() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:busyjobs" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__conversion_jobs.sql"))
                .execute(source);
        var conversions = mock(ConversionService.class);
        when(conversions.runReservedJob(
                        any(), isNull(), isNull(), anyString(), eq("file-compress")))
                .thenThrow(new ConversionBusyException());
        var settings = TestSettings.settings();
        var jobs =
                new ConversionJobService(
                        jdbc,
                        new DataSourceTransactionManager(source),
                        conversions,
                        settings,
                        new JobSettings(),
                        new ObjectMapper(),
                        directory);
        ReflectionTestUtils.setField(jobs, "maxFileMb", 50);
        try {
            var accepted =
                    jobs.submit(
                            "file-compress",
                            new MockMultipartFile(
                                    "file", "busy.txt", "text/plain", "data".getBytes()),
                            UUID.randomUUID().toString(),
                            null,
                            null,
                            "127.0.0.1");
            assertTimeoutPreemptively(Duration.ofSeconds(3), jobs::dispatch);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            "queued", jobs.get(accepted.jobId(), null).status()));
            verify(conversions, atMost(settings.getMaxConcurrent()))
                    .runReservedJob(any(), isNull(), isNull(), anyString(), eq("file-compress"));
            jobs.cancel(accepted.jobId(), null);
        } finally {
            jobs.close();
        }
    }
}
