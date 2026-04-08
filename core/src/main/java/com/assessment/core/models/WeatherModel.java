package com.assessment.core.models;

import javax.annotation.PostConstruct;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import com.assessment.core.services.WeatherBean;
import com.assessment.core.services.WeatherService;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

@Model(
        adaptables = {SlingHttpServletRequest.class, Resource.class},
        defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WeatherModel {

    @ValueMapValue
    private String city;

    @OSGiService
    private WeatherService weatherService;

    @SlingObject
    private Resource resource;

    private WeatherBean weatherData;
    private Page currentPage;

    @PostConstruct
    protected void init() {
        if (resource != null) {
            PageManager pageManager = resource.getResourceResolver().adaptTo(PageManager.class);
            if (pageManager != null) {
                this.currentPage = pageManager.getContainingPage(resource);
            }
        }

        if (weatherService != null) {
            weatherData = weatherService.getForecast(city, resource);
        }

        if (weatherData == null) {
            weatherData = WeatherBean.unavailable(city != null ? city : "Unknown");
        }
    }
    
    public String getPageTitle() {
        if (currentPage != null) {
            String title = currentPage.getTitle();
            if (title != null && !title.isEmpty()) {
                return title;
            }
            return currentPage.getName();
        }
        return "Weather Page";
    }

    public String getCity() {
        return weatherData.getCity();
    }

    public String getTemperature() {
        return weatherData.getTemperature();
    }

    public String getDescription() {
        return weatherData.getDescription();
    }

    public boolean isAvailable() {
        return weatherData.isAvailable();
    }

    public boolean isFromCache() {
        return weatherData.isFromCache();
    }
}