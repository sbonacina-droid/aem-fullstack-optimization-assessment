package com.assessment.core.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.assessment.core.services.WeatherBean;
import com.assessment.core.services.WeatherService;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class WeatherModelTest {

    private final AemContext ctx = new AemContext();

    @Mock
    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        ctx.addModelsForClasses(WeatherModel.class);
        ctx.registerService(WeatherService.class, weatherService);
    }

    @Test
    void testValidModelData() {
    	Page page = ctx.create().page("/content/mysite/en", "dummy/template", 
                "jcr:title", "My Weather Page");
        
        Resource component = ctx.create().resource(page.getContentResource().getPath() + "/weather", 
                "city", "Milan");
        ctx.currentResource(component);

        WeatherBean mockBean = WeatherBean.of("Milan", "22 C", "Sunny").withFromCache(true);
        when(weatherService.getForecast(eq("Milan"), any(Resource.class))).thenReturn(mockBean);

        WeatherModel model = ctx.request().adaptTo(WeatherModel.class);

        assertEquals("My Weather Page", model.getPageTitle());
        assertEquals("Milan", model.getCity());
        assertEquals("22 C", model.getTemperature());
        assertEquals("Sunny", model.getDescription());
        assertTrue(model.isAvailable());
        assertTrue(model.isFromCache());
    }

    @Test
    void testFallbackPageTitle() {
        // Setup a page without a "jcr:title" property
        Page page = ctx.create().page("/content/mysite/weather-page");
        Resource component = ctx.create().resource(page.getContentResource().getPath() + "/weather", 
                "city", "Rome");
        ctx.currentResource(component);

        when(weatherService.getForecast(eq("Rome"), any(Resource.class)))
                .thenReturn(WeatherBean.unavailable("Rome"));

        WeatherModel model = ctx.request().adaptTo(WeatherModel.class);

        // Should fall back to the node name
        assertEquals("weather-page", model.getPageTitle());
        assertFalse(model.isAvailable());
    }

    @Test
    void testStandaloneResourceWithoutPage() {
        // Create a standalone resource outside of the /content hierarchy (no containing page)
        Resource component = ctx.create().resource("/var/commerce/weather", "city", "London");
        ctx.currentResource(component);

        when(weatherService.getForecast(eq("London"), any(Resource.class)))
                .thenReturn(WeatherBean.unavailable("London"));

        WeatherModel model = ctx.request().adaptTo(WeatherModel.class);

        // Should fall back to the hardcoded default title
        assertEquals("Weather Page", model.getPageTitle());
        assertEquals("London", model.getCity());
        assertFalse(model.isAvailable());
    }

    @Test
    void testMissingCityAndServiceFailure() {
        // Create resource without the "city" property
        Resource component = ctx.create().resource("/content/test/weather");
        ctx.currentResource(component);

        // Simulate the service returning null (e.g., severe service failure)
        when(weatherService.getForecast(eq(null), any(Resource.class))).thenReturn(null);

        WeatherModel model = ctx.request().adaptTo(WeatherModel.class);

        // Assert fallback logic in init() kicks in to prevent NullPointerExceptions
        assertEquals("Unknown", model.getCity());
        assertEquals("", model.getTemperature());
        assertFalse(model.isAvailable());
    }
}