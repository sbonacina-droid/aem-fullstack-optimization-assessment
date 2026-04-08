package com.assessment.core.services;

import java.util.Objects;

public final class WeatherBean {

    private final String city;
    private final String temperature;
    private final String description;
    private final boolean available;
    private final boolean fromCache;

    private WeatherBean(
            String city,
            String temperature,
            String description,
            boolean available,
            boolean fromCache) {
        this.city = city != null ? city : "";
        this.temperature = temperature != null ? temperature : "";
        this.description = description != null ? description : "";
        this.available = available;
        this.fromCache = fromCache;
    }

    public static WeatherBean of(String city, String temperature, String description) {
        return new WeatherBean(city, temperature, description, true, false);
    }

    public static WeatherBean unavailable(String city) {
        return new WeatherBean(city, "", "", false, false);
    }

    public WeatherBean withFromCache(boolean cacheHit) {
        return new WeatherBean(city, temperature, description, available, cacheHit);
    }

    public String getCity() { return city; }
    public String getTemperature() { return temperature; }
    public String getDescription() { return description; }
    public boolean isAvailable() { return available; }
    public boolean isFromCache() { return fromCache; }

    @Override
    public String toString() {
        return "WeatherBean{" +
                "city='" + city + '\'' +
                ", temperature='" + temperature + '\'' +
                ", description='" + description + '\'' +
                ", available=" + available +
                ", fromCache=" + fromCache +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WeatherBean that = (WeatherBean) o;
        return available == that.available &&
                fromCache == that.fromCache &&
                city.equals(that.city) &&
                temperature.equals(that.temperature) &&
                description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(city, temperature, description, available, fromCache);
    }
}