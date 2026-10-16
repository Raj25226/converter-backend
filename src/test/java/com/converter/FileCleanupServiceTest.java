package com.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.converter.entity.Conversion;
import com.converter.repository.*;
import com.converter.service.FileCleanupService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class FileCleanupServiceTest {
    @TempDir Path directory;

    private Conversion conversion(long id, String file) {
        var result = new Conversion();
        result.setId(id);
        result.setFileId(file);
        result.setExpiresAt(Instant.now().minusSeconds(100));
        return result;
    }

    @Test
    void cleanupUsesBoundedBatchesAndClearsOnlySuccessfullyRemovedFiles() throws Exception {
        var repository = mock(ConversionRepository.class);
        var service =
                new FileCleanupService(repository, mock(EmailTokenRepository.class), directory);
        ReflectionTestUtils.setField(service, "historyRetentionDays", 30L);
        ReflectionTestUtils.setField(service, "batchSize", 2);
        var good = conversion(1, "good");
        var bad = conversion(2, "bad");
        Files.writeString(directory.resolve("good.pdf"), "expired");
        Files.createDirectory(directory.resolve("bad.pdf"));
        Files.writeString(directory.resolve("bad.pdf/child"), "cannot remove nonempty directory");
        when(repository.findExpiredBatch(any(), eq(0L), any())).thenReturn(List.of(good, bad));
        when(repository.findExpiredBatch(any(), eq(2L), any())).thenReturn(List.of());
        when(repository.findRetiredBatch(any(), any())).thenReturn(List.of());
        service.cleanupExpiredFiles();
        assertFalse(Files.exists(directory.resolve("good.pdf")));
        assertTrue(Files.exists(directory.resolve("bad.pdf/child")));
        verify(repository).clearExpiredFiles(eq(List.of(1L)), any());
        verify(repository, times(2))
                .findExpiredBatch(any(), anyLong(), argThat(page -> page.getPageSize() == 2));
    }

    @Test
    void cleanupStopsAtTheConfiguredBatchBudget() {
        var repository = mock(ConversionRepository.class);
        var service =
                new FileCleanupService(repository, mock(EmailTokenRepository.class), directory);
        ReflectionTestUtils.setField(service, "historyRetentionDays", 30L);
        ReflectionTestUtils.setField(service, "batchSize", 1);
        ReflectionTestUtils.setField(service, "maxBatches", 1);
        when(repository.findExpiredBatch(any(), anyLong(), any()))
                .thenReturn(List.of(conversion(1, "absent")));
        when(repository.findRetiredBatch(any(), any())).thenReturn(List.of(42L));
        service.cleanupExpiredFiles();
        verify(repository, times(1)).findExpiredBatch(any(), anyLong(), any());
        verify(repository, times(1)).deleteRetiredBatch(eq(List.of(42L)), any());
    }
}
