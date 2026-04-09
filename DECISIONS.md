# AEM Project key architectural changes

This document outlines key architectural choices such as caching strategy, configuration strategy, and Dispatcher hardening decisions

## 1. Update projects pom and structure to make it buildable with a standard autoInstallSinglePackage profile on AEMaaCS

### 1. Modules update
* Fixed `jcr:content` to `_jcr_content` folder
* Fixed editable template and policy
* Added `org.osgi.service.metatype.annotations`
* Added dependency for unit test
* Fixed pom and added autoInstallSinglePackage profile



## 2. Configuration Strategy (tenant-aware)
* Replaced legacy global weather config usage with Context-Aware 
* Weather parameters are tenant-scoped:
** apiKey
** endpoint
** defaultCity
** ttlCache
** networkTimeoutMillis
* OSGI config for default if not tenant is provided

## 3. Frontend
* Removed inline JavaScript and client-side weather fetch logic as requested.
* No sercret/apikey is displayed in the html

## 4. Backend
* Replaced HttpConnection with HttpClient
* Managed timeout connection
* ObjectMapper to map the JSON (typed)
* Added in memory TTL cache as requested

## 5. Dispatcher
* Updated filters.any to allow only strictly required paths
* completely blocked /bin (in real use case, only required bin path should be enabled)
* limited etc.clientlibs extensions

## 6. Tests
* Add unit tests for backend logic
