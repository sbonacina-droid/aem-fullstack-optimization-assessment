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

	private static final Pattern DISABLED_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\s\\-]");

	private static final String DEFAULT_ENDPOINT = "https://goweather.xyz/weather/{city}?apikey={apiKey}";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@ObjectClassDefinition(name = "Assessment Weather Service")
	public @interface OsgiConfig {
		@AttributeDefinition(name = "Default request template")
		String default_request_template() default DEFAULT_ENDPOINT;

		@AttributeDefinition(name = "Weather API key")
		String api_key() default "";

		@AttributeDefinition(name = "Default city")
		String default_city() default "Madrid";

		@AttributeDefinition(name = "Default network timeout (ms)")
		int default_network_timeout_millis() default 2000;

		@AttributeDefinition(name = "Default cache TTL (seconds)")
		int default_cache_ttl_seconds() default 300;
	}

	private final Map<String, ForecastCacheEntry> forecastCache = new ConcurrentHashMap<>();

	private final Map<String, CompletableFuture<WeatherBean>> pendingRequests = new ConcurrentHashMap<>();

	private final Clock clock;

	private HttpClient httpClient;
	private String defaultRequestTemplate;
	private String globalApiKey;
	private String defaultCity;
	private int defaultNetworkTimeoutMillis;
	private int defaultCacheTtlSeconds;

	public WeatherServiceImpl() {
		this(Clock.systemUTC());
	}

	WeatherServiceImpl(Clock clock) {
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder().build();
		this.defaultRequestTemplate = DEFAULT_ENDPOINT;
		this.globalApiKey = "";
		this.defaultCity = "Madrid";
		this.defaultNetworkTimeoutMillis = 2000;
		this.defaultCacheTtlSeconds = 300;
	}

	@Activate
	protected void activate(OsgiConfig config) {
		this.defaultRequestTemplate = defaultIfBlank(config.default_request_template(), DEFAULT_ENDPOINT);
		this.globalApiKey = defaultIfBlank(config.api_key(), "");
		this.defaultCity = defaultIfBlank(config.default_city(), "Madrid");
		this.defaultNetworkTimeoutMillis = Math.max(100, config.default_network_timeout_millis());
		this.defaultCacheTtlSeconds = Math.max(1, config.default_cache_ttl_seconds());

		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(defaultNetworkTimeoutMillis))
				.build();
		this.forecastCache.clear();
		this.pendingRequests.clear();
	}

	@Override
	public WeatherBean getForecast(String city, Resource contextResource) {
		WeatherApiSettings settings = resolveTenantSettings(contextResource);
		String safeCity = getSafeCity(city).isEmpty() ? settings.defaultCity : getSafeCity(city);

		String cacheKey = (settings.endpoint + "|" + settings.apiKey + "|" + safeCity).toLowerCase(Locale.ROOT);

		ForecastCacheEntry entry = forecastCache.get(cacheKey);
		if (entry != null && entry.isFresh(clock.millis())) {
			return entry.weatherData.withFromCache(true);
		}

		CompletableFuture<WeatherBean> refresh = pendingRequests.computeIfAbsent(cacheKey,
				key -> loadWeatherAsync(cacheKey, safeCity, settings));
		refresh.whenComplete((result, error) -> pendingRequests.remove(cacheKey));

		if (entry != null) {
			return entry.weatherData.withFromCache(true);
		}

		try {
			return refresh.get(Math.max(100, settings.networkTimeoutMillis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOG.warn("Request was interrupted for city {}", safeCity, e);
			return WeatherBean.unavailable(safeCity);
		} catch (Exception e) {
			LOG.warn("Request failed for city {}", safeCity, e);
			return WeatherBean.unavailable(safeCity);
		}
	}

	private CompletableFuture<WeatherBean> loadWeatherAsync(String cacheKey, String city, WeatherApiSettings settings) {
		String requestUrl = buildRequestUrl(settings, city);
		int timeoutMillis = Math.max(100, settings.networkTimeoutMillis);

		return callWeatherApiAsync(requestUrl, timeoutMillis).handle((payload, error) -> {
			if (error != null) {
				LOG.warn("Weather request failed for city {}", city, error);
				return WeatherBean.unavailable(city);
			}
			return getWeatherBean(city, payload);
		}).thenApply(weatherData -> {
			long ttlMillis = Math.max(1, settings.cacheTtlSeconds) * 1000L;
			if (forecastCache.size() > 1000) {
				LOG.warn("Weather cache exceeded 1000 entries. Clearing cache to prevent OOM.");
				forecastCache.clear();
			}
			forecastCache.put(cacheKey, new ForecastCacheEntry(weatherData, clock.millis() + ttlMillis));
			return weatherData;
		});
	}

	protected CompletableFuture<String> callWeatherApiAsync(String requestUrl, int timeoutMillis) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(requestUrl)).header("Accept", "application/json")
				.timeout(Duration.ofMillis(timeoutMillis)).GET().build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					if (response.statusCode() < 200 || response.statusCode() >= 300) {
						String errorMsg = String.format("API Error: %d. Details: %s", response.statusCode(),
								response.body());
						throw new CompletionException(new IllegalStateException(errorMsg));
					}
					return response.body();
				});
	}

	private WeatherApiSettings resolveTenantSettings(Resource contextResource) {
		if (contextResource != null) {
			ConfigurationBuilder configBuilder = contextResource.adaptTo(ConfigurationBuilder.class);
			if (configBuilder != null) {
				WeatherConfig tenantConfig = configBuilder.as(WeatherConfig.class);
				if (tenantConfig != null) {
					return new WeatherApiSettings(defaultIfBlank(tenantConfig.apiKey(), globalApiKey),
							defaultIfBlank(tenantConfig.endpoint(), defaultRequestTemplate),
							defaultIfBlank(tenantConfig.defaultCity(), defaultCity),
							Math.max(1, tenantConfig.ttlCache()), Math.max(100, tenantConfig.networkTimeoutMillis()));
				}
			}
		}
		return new WeatherApiSettings(globalApiKey, defaultRequestTemplate, defaultCity, defaultCacheTtlSeconds,
				defaultNetworkTimeoutMillis);
	}

	private String buildRequestUrl(WeatherApiSettings settings, String city) {
		String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
		String encodedApiKey = URLEncoder.encode(settings.apiKey, StandardCharsets.UTF_8);

		return settings.endpoint.replace("{city}", encodedCity).replace("{apiKey}", encodedApiKey);
	}

	private String getSafeCity(String city) {
		return city == null ? "" : DISABLED_PATTERN.matcher(city).replaceAll("").trim();
	}

	private String defaultIfBlank(String value, String fallback) {
		return (value == null || value.isBlank()) ? fallback : value;
	}

	private WeatherBean getWeatherBean(String city, String jsonContent) {
		if (jsonContent == null || jsonContent.isBlank()) {
			return WeatherBean.unavailable(city);
		}

		try {
			JsonNode root = MAPPER.readTree(jsonContent);

			String temperature = root.path("temperature").asText("");
			String description = root.path("description").asText("");

			if (temperature.isEmpty() && root.has("main")) {
				temperature = root.path("main").path("temp").asText("") + " C";
			}
			if (description.isEmpty() && root.path("weather").isArray()) {
				description = root.path("weather").path(0).path("description").asText("");
			}

			if (temperature.isEmpty() && description.isEmpty())
				return WeatherBean.unavailable(city);
			return WeatherBean.of(city, temperature, description);
		} catch (Exception e) {
			LOG.warn("Failed to parse JSON response for city: {}", city, e);
			return WeatherBean.unavailable(city);
		}
	}

	private static final class WeatherApiSettings {
		final String apiKey;
		final String endpoint;
		final String defaultCity;
		final int networkTimeoutMillis;
		final int cacheTtlSeconds;

		WeatherApiSettings(String apiKey, String endpoint, String defaultCity, int cacheTtlSeconds,
				int networkTimeoutMillis) {
			this.apiKey = apiKey;
			this.endpoint = endpoint;
			this.defaultCity = defaultCity;
			this.networkTimeoutMillis = networkTimeoutMillis;
			this.cacheTtlSeconds = cacheTtlSeconds;
		}
	}

	private static final class ForecastCacheEntry {
		final WeatherBean weatherData;
		final long expiresAt;

		ForecastCacheEntry(WeatherBean weatherData, long expiresAt) {
			this.weatherData = weatherData;
			this.expiresAt = expiresAt;
		}

		boolean isFresh(long currentTimeMillis) {
			return currentTimeMillis < expiresAt;
		}
	}
}