package com.assessment.core.services;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WeatherBeanTest {

    @Test
    void testOf_ValidData() {
        WeatherBean bean = WeatherBean.of("Madrid", "25 C", "Sunny");

        assertEquals("Madrid", bean.getCity());
        assertEquals("25 C", bean.getTemperature());
        assertEquals("Sunny", bean.getDescription());
        assertTrue(bean.isAvailable(), "Bean created via of() should be available");
        assertFalse(bean.isFromCache(), "Bean created via of() should not be from cache by default");
    }

    @Test
    void testUnavailable() {
        WeatherBean bean = WeatherBean.unavailable("London");

        assertEquals("London", bean.getCity());
        assertEquals("", bean.getTemperature(), "Unavailable bean should have empty temperature");
        assertEquals("", bean.getDescription(), "Unavailable bean should have empty description");
        assertFalse(bean.isAvailable(), "Unavailable bean should not be available");
        assertFalse(bean.isFromCache(), "Unavailable bean should not be from cache");
    }

    @Test
    void testNullSafety() {
        WeatherBean bean = WeatherBean.of(null, null, null);

        assertEquals("", bean.getCity(), "Null city should be converted to empty string");
        assertEquals("", bean.getTemperature(), "Null temperature should be converted to empty string");
        assertEquals("", bean.getDescription(), "Null description should be converted to empty string");
    }

    @Test
    void testWithFromCache() {
        WeatherBean original = WeatherBean.of("Milan", "20 C", "Cloudy");
        WeatherBean cached = original.withFromCache(true);

        // Verify the original object is immutable and unchanged
        assertFalse(original.isFromCache());

        // Verify the new object has the updated cache flag but same data
        assertTrue(cached.isFromCache());
        assertEquals(original.getCity(), cached.getCity());
        assertEquals(original.getTemperature(), cached.getTemperature());
        assertEquals(original.getDescription(), cached.getDescription());
        assertEquals(original.isAvailable(), cached.isAvailable());
    }

    @Test
    void testEqualsAndHashCode() {
        WeatherBean bean1 = WeatherBean.of("Rome", "30 C", "Hot");
        WeatherBean bean2 = WeatherBean.of("Rome", "30 C", "Hot");
        WeatherBean differentCity = WeatherBean.of("Venice", "30 C", "Hot");
        WeatherBean differentCacheStatus = bean1.withFromCache(true);

        // Test equals
        assertEquals(bean1, bean1, "Object should equal itself");
        assertEquals(bean1, bean2, "Identical objects should be equal");
        assertNotEquals(bean1, differentCity, "Different data should not be equal");
        assertNotEquals(bean1, differentCacheStatus, "Different cache status should not be equal");
        assertNotEquals(bean1, null, "Should not equal null");
        assertNotEquals(bean1, "Some String", "Should not equal different class type");

        // Test hashCode
        assertEquals(bean1.hashCode(), bean2.hashCode(), "Equal objects must have equal hash codes");
        assertNotEquals(bean1.hashCode(), differentCity.hashCode(), "Different objects should ideally have different hash codes");
    }

    @Test
    void testToString() {
        WeatherBean bean = WeatherBean.of("Paris", "15 C", "Rainy");
        String beanString = bean.toString();

        assertTrue(beanString.contains("WeatherBean{"));
        assertTrue(beanString.contains("city='Paris'"));
        assertTrue(beanString.contains("temperature='15 C'"));
        assertTrue(beanString.contains("description='Rainy'"));
        assertTrue(beanString.contains("available=true"));
        assertTrue(beanString.contains("fromCache=false"));
    }
}