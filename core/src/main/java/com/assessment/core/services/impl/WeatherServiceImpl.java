package com.assessment.core.services.impl;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.caconfig.ConfigurationBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.assessment.core.config.WeatherConfig;
import com.assessment.core.services.WeatherBean;
import com.assessment.core.services.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component(service = WeatherService.class, immediate = true)
@Designate(ocd = WeatherServiceImpl.OsgiConfig.class)
public class WeatherServiceImpl implements WeatherService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherServiceImpl.class);
    private static final String DEFAULT_WEATHER_REQUEST_TEMPLATE = "https://goweather.xyz/weather/{city}?apikey={apiKey}";
    private static final Pattern DISALLOWED_CITY_CHARS = Pattern.compile("[^\\p{L}\\p{N}\\s\\-]");
    
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ObjectClassDefinition(name = "Assessment Weather Service")
    public @interface OsgiConfig {
        @AttributeDefinition(name = "Weather API key") String api_key() default "";
        @AttributeDefinition(name = "Default request template") String default_request_template() default DEFAULT_WEATHER_REQUEST_TEMPLATE;
        @AttributeDefinition(name = "Default city") String default_city() default "Madrid";
        @AttributeDefinition(name = "Default cache TTL (seconds)") int default_cache_ttl_seconds() default 300;
        @AttributeDefinition(name = "Default network timeout (ms)") int default_network_timeout_millis() default 2000;
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<WeatherBean>> inFlight = new ConcurrentHashMap<>();
    private final Clock clock;
    
    private HttpClient httpClient;
    private String globalApiKey; // Renamed for clarity
    private String defaultRequestTemplate;
    private String defaultCity;
    private int defaultCacheTtlSeconds;
    private int defaultNetworkTimeoutMillis;

    public WeatherServiceImpl() {
        this(Clock.systemUTC());
    }

    WeatherServiceImpl(Clock clock) {
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder().build();
        this.globalApiKey = "";
        this.defaultRequestTemplate = DEFAULT_WEATHER_REQUEST_TEMPLATE;
        this.defaultCity = "Madrid";
        this.defaultCacheTtlSeconds = 300;
        this.defaultNetworkTimeoutMillis = 2000;
    }

    @Activate
    protected void activate(OsgiConfig config) {
        this.globalApiKey = valueOrDefault(config.api_key(), "");
        this.defaultRequestTemplate = valueOrDefault(config.default_request_template(), DEFAULT_WEATHER_REQUEST_TEMPLATE);
        this.defaultCity = valueOrDefault(config.default_city(), "Madrid");
        this.defaultCacheTtlSeconds = Math.max(1, config.default_cache_ttl_seconds());
        this.defaultNetworkTimeoutMillis = Math.max(100, config.default_network_timeout_millis());
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(defaultNetworkTimeoutMillis))
                .build();
        this.cache.clear();
        this.inFlight.clear();
    }

    @Override
    public WeatherBean getForecast(String city, Resource contextResource) {
        TenantSettings settings = resolveTenantSettings(contextResource);
        String effectiveCity = sanitizeCity(city).isEmpty() ? settings.defaultCity : sanitizeCity(city);
        
        String cacheKey = (settings.requestTemplate + "|" + settings.apiKey + "|" + effectiveCity).toLowerCase(Locale.ROOT);

        CacheEntry current = cache.get(cacheKey);
        if (current != null && current.isFresh(clock.millis())) {
            return current.weatherData.withFromCache(true);
        }

        CompletableFuture<WeatherBean> refresh = inFlight.computeIfAbsent(
                cacheKey, key -> fetchAndCache(cacheKey, effectiveCity, settings));
        refresh.whenComplete((result, error) -> inFlight.remove(cacheKey));

        if (current != null) {
            return current.weatherData.withFromCache(true);
        }

        try {
            return refresh.get(Math.max(100, settings.networkTimeoutMillis), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("Weather lookup failed or timed out for city {}", effectiveCity, e);
            return WeatherBean.unavailable(effectiveCity);
        }
    }

    private CompletableFuture<WeatherBean> fetchAndCache(String cacheKey, String city, TenantSettings settings) {
        String requestUrl = buildRequestUrl(settings, city);
        int timeoutMillis = Math.max(100, settings.networkTimeoutMillis);

        return executeHttpRequest(requestUrl, timeoutMillis)
                .handle((payload, error) -> {
                    if (error != null) {
                        LOG.warn("Weather request failed for city {}", city, error);
                        return WeatherBean.unavailable(city);
                    }
                    return getWeatherBean(city, payload);
                })
                .thenApply(weatherData -> {
                    long ttlMillis = Math.max(1, settings.cacheTtlSeconds) * 1000L;
                    if (cache.size() > 1000) {
                        LOG.warn("Weather cache exceeded 1000 entries. Clearing cache to prevent OOM.");
                        cache.clear();
                    }
                    cache.put(cacheKey, new CacheEntry(weatherData, clock.millis() + ttlMillis));
                    return weatherData;
                });
    }

    protected CompletableFuture<String> executeHttpRequest(String requestUrl, int timeoutMillis) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new CompletionException(new IllegalStateException("API Error: " + response.statusCode()));
                    }
                    return response.body();
                });
    }

    private WeatherBean getWeatherBean(String city, String payload) {
        if (payload == null || payload.isBlank()) return WeatherBean.unavailable(city);
        try {
            JsonNode root = MAPPER.readTree(payload);
            String temp = root.path("temperature").asText("");
            String desc = root.path("description").asText("");

            if (temp.isEmpty() && root.has("main")) {
                temp = root.path("main").path("temp").asText("") + " C";
            }
            if (desc.isEmpty() && root.has("weather") && root.path("weather").isArray()) {
                desc = root.path("weather").get(0).path("description").asText("");
            }

            if (temp.isEmpty() && desc.isEmpty()) return WeatherBean.unavailable(city);
            return WeatherBean.of(city, temp, desc, payload);
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON response for city: {}", city, e);
            return WeatherBean.unavailable(city);
        }
    }

    private TenantSettings resolveTenantSettings(Resource contextResource) {
        if (contextResource != null) {
            ConfigurationBuilder configBuilder = contextResource.adaptTo(ConfigurationBuilder.class);
            if (configBuilder != null) {
                WeatherConfig tenantConfig = configBuilder.as(WeatherConfig.class);
                if (tenantConfig != null) {
                    return new TenantSettings(
                            valueOrDefault(tenantConfig.apiKey(), globalApiKey),
                            valueOrDefault(tenantConfig.endpoint(), defaultRequestTemplate),
                            valueOrDefault(tenantConfig.defaultCity(), defaultCity),
                            Math.max(1, tenantConfig.ttlCache()),
                            Math.max(100, tenantConfig.networkTimeoutMillis())
                    );
                }
            }
        }
        return new TenantSettings(globalApiKey, defaultRequestTemplate, defaultCity, defaultCacheTtlSeconds, defaultNetworkTimeoutMillis);
    }

    private String buildRequestUrl(TenantSettings settings, String city) {
        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String encodedApiKey = URLEncoder.encode(settings.apiKey, StandardCharsets.UTF_8);
        
        return settings.requestTemplate
                .replace("{city}", encodedCity)
                .replace("{apiKey}", encodedApiKey);
    }

    private String sanitizeCity(String city) {
        return city == null ? "" : DISALLOWED_CITY_CHARS.matcher(city).replaceAll("").trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static final class TenantSettings {
        final String apiKey;
        final String requestTemplate;
        final String defaultCity;
        final int cacheTtlSeconds;
        final int networkTimeoutMillis;

        TenantSettings(String apiKey, String requestTemplate, String defaultCity, int cacheTtlSeconds, int networkTimeoutMillis) {
            this.apiKey = apiKey;
            this.requestTemplate = requestTemplate;
            this.defaultCity = defaultCity;
            this.cacheTtlSeconds = cacheTtlSeconds;
            this.networkTimeoutMillis = networkTimeoutMillis;
        }
    }

    private static final class CacheEntry {
        final WeatherBean weatherData;
        final long expiresAt;

        CacheEntry(WeatherBean weatherData, long expiresAt) {
            this.weatherData = weatherData;
            this.expiresAt = expiresAt;
        }

        boolean isFresh(long now) {
            return now < expiresAt;
        }
    }
}