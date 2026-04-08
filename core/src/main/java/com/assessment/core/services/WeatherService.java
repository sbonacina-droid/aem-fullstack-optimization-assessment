package com.assessment.core.services;

import org.apache.sling.api.resource.Resource;

public interface WeatherService {

    WeatherBean getForecast(String city, Resource contextResource);
}

