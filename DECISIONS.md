# AEM Project key architectural changes

This document outlines key architectural choices such as caching strategy, configuration strategy, and Dispatcher hardening decisions

## 1. Update projects pom and structure to make it buildable with a standard autoInstallSinglePackage profile on AEMaaCS

### 1. `ui.apps` and `ui.config` Modules
* Fixed `jcr:content` to `_jcr_content` folder
* Fixed editable template and policy
* Added `org.osgi.service.metatype.annotations`
* Added dependency for unit test



## 2 Configuration Strategy (tenant-aware)
* Replaced legacy global weather config usage with Context-Aware 
* Weather parameters are tenant-scoped:
** apiKey
** endpoint
** defaultCity
** ttlCache
** networkTimeoutMillis
* OSGI config for default if not tenant is provided
