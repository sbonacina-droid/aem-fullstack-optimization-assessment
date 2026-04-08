package com.assessment.core.config;

import org.apache.sling.caconfig.annotation.Configuration;
import org.apache.sling.caconfig.annotation.Property;

@Configuration(
    label = "Assessment Weather Tenant Configuration",
    description = "Tenant-specific settings for the Weather API Integration"
)
public @interface WeatherConfig {

    @Property(
        label = "Weather API Key",
        description = "The secret API key for the weather provider."
    )
    String apiKey() default "";

    @Property(
        label = "Weather Request endpoint",
        description = "URL template. Use {city} and {apiKey} as placeholders."
    )
    String endpoint() default "https://goweather.xyz/weather/{city}?apikey={apiKey}";

    @Property(
        label = "Default City",
        description = "City used if no city is specified in the component dialog."
    )
    String defaultCity() default "Madrid";

    @Property(
        label = "TTL Cache (seconds)",
        description = "How long to keep weather data in memory. Default is 10 minutes (600s)."
    )
    int ttlCache() default 600;

    @Property(
        label = "Network Timeout (ms)",
        description = "Maximum time to wait for the weather service to respond."
    )
    int networkTimeoutMillis() default 2000;
}