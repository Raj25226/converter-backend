package com.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.converter.exception.RateLimitException;
import com.converter.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuotaServiceTest {
    @Test
    void exhaustedReservationReturnsRateLimit() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThrows(
                RateLimitException.class,
                () -> new QuotaService(jdbc).reserve(null, "127.0.0.1", 2));
    }

    @Test
    void successfulReservationDoesNotThrow() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertDoesNotThrow(() -> new QuotaService(jdbc).reserve(1L, null, 10));
    }
}
