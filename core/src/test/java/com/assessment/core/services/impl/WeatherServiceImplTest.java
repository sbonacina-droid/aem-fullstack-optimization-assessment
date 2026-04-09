package com.assessment.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

import org.apache.sling.testing.mock.caconfig.MockContextAwareConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.assessment.core.config.WeatherConfig;
import com.assessment.core.services.WeatherBean;
import com.assessment.core.services.WeatherService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextBuilder;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith({AemContextExtension.class})
class WeatherServiceImplTest {

    private final AemContext ctx = new AemContextBuilder()
            .plugin(org.apache.sling.testing.mock.caconfig.ContextPlugins.CACONFIG)
            .build();

    private WeatherServiceImpl weatherService;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        // Use a fixed clock to make testing time-based cache expiration deterministic
        mutableClock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
        
        // Register CAConfig classes
        MockContextAwareConfig.registerAnnotationClasses(ctx, WeatherConfig.class);
    }

    /**
     * Creates a fake OSGi config using native Java Proxy, completely bypassing Mockito 
     * and Byte Buddy to ensure Java 23 compatibility.
     */
    private WeatherServiceImpl.OsgiConfig createMockConfig(String city, int timeout, int ttl) {
        return (WeatherServiceImpl.OsgiConfig) Proxy.newProxyInstance(
            WeatherServiceImpl.OsgiConfig.class.getClassLoader(),
            new Class<?>[] { WeatherServiceImpl.OsgiConfig.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "api_key": return "test-key";
                    case "default_city": return city;
                    case "default_network_timeout_millis": return timeout;
                    case "default_cache_ttl_seconds": return ttl;
                    case "default_request_template": return "https://goweather.xyz/weather/{city}?apikey={apiKey}";
                    default: return null;
                }
            }
        );
    }

    @Test
    void testSuccessfulWeatherFetch() {
        // 1. Create service with anonymous override to mock the network call
        weatherService = new WeatherServiceImpl(mutableClock) {
            @Override
            protected CompletableFuture<String> callWeatherApiAsync(String url, int timeout) {
                return CompletableFuture.completedFuture("{\"temperature\":\"25 C\", \"description\":\"Sunny\"}");
            }
        };

        // 2. Create the mock config using pure Java
        WeatherServiceImpl.OsgiConfig mockConfig = createMockConfig("Madrid", 2000, 300);

        // 3. MANUALLY activate - this avoids NoScrMetadataException
        weatherService.activate(mockConfig);

        // 4. Register as a service so other components could find it in the mock registry
        ctx.registerService(WeatherService.class, weatherService);

        // 5. Execute
        WeatherBean result = weatherService.getForecast("Madrid", null);

        // 6. Assert
        assertTrue(result.isAvailable());
        assertEquals("Madrid", result.getCity());
        assertEquals("25 C", result.getTemperature());
        assertEquals("Sunny", result.getDescription());
        assertFalse(result.isFromCache());
    }

    @Test
    void testApiFailureReturnsUnavailable() {
        // 1. Create service that simulates an HTTP failure
        weatherService = new WeatherServiceImpl(mutableClock) {
            @Override
            protected CompletableFuture<String> callWeatherApiAsync(String url, int timeout) {
                CompletableFuture<String> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException("Connection Timeout"));
                return failedFuture;
            }
        };

        // 2. Create the mock config
        WeatherServiceImpl.OsgiConfig mockConfig = createMockConfig("Madrid", 100, 300);
        
        // 3. Activate
        weatherService.activate(mockConfig);

        // 4. Execute
        WeatherBean result = weatherService.getForecast("Rome", null);

        // 5. Assert
        assertFalse(result.isAvailable(), "Service should return unavailable bean on failure");
        assertEquals("Rome", result.getCity());
        assertEquals("", result.getTemperature());
    }

    @Test
    void testCacheLogic() {
        // 1. Create service with a static response
        weatherService = new WeatherServiceImpl(mutableClock) {
            @Override
            protected CompletableFuture<String> callWeatherApiAsync(String url, int timeout) {
                return CompletableFuture.completedFuture("{\"temperature\":\"10 C\", \"description\":\"Cloudy\"}");
            }
        };

        // 2. Create Config with 5 minute TTL
        WeatherServiceImpl.OsgiConfig mockConfig = createMockConfig("London", 2000, 300);
        
        // 3. Activate
        weatherService.activate(mockConfig);

        // First call - Cache Miss
        WeatherBean first = weatherService.getForecast("London", null);
        assertFalse(first.isFromCache());

        // Second call - Cache Hit
        WeatherBean second = weatherService.getForecast("London", null);
        assertTrue(second.isFromCache());
        assertEquals("10 C", second.getTemperature());
    }
}