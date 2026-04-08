# AEM Project key architectural changes

This document outlines key architectural choices such as caching strategy, configuration strategy, and Dispatcher hardening decisions

## 1. Update projects pom and structure to make it buildable with a standard autoInstallSinglePackage profile on AEMaaCS

### 1. `ui.apps` and `ui.config` Modules

**Issue:** Packages were failing FileVault validation due to `jackrabbit-overlappingfilter` errors. The analyzer detected that these packages shared the `/apps/assessment` path with the `ui.apps.structure` package.
**Fixes Applied:**
* Added `assessment.ui.apps.structure` (with `<type>zip</type>`) to the main `<dependencies>` block in both `pom.xml` files.
* Added `assessment.ui.apps.structure` to the `<dependencies>` configuration inside the `filevault-package-maven-plugin` block to explicitly declare the relationship.

**Issue:** `ui.apps` failed strict validation because it is an `application` package but contained an OSGi bundle (`assessment.core`).
**Fixes Applied:**
* Removed the `assessment.core` `<embedded>` block from the `ui.apps/pom.xml`. (AEMaaCS requires Java bundles to be handled by the container package).

### 2. `all` Module (Container Package)

**Issue:** The build failed during the `all` module because it attempted to re-validate sub-packages and lost their metadata.
**Fixes Applied:**
* Added `<skipSubPackageValidation>true</skipSubPackageValidation>` to the `filevault-package-maven-plugin` configuration in `all/pom.xml`.

**Issue:** Missing deployment profile caused Maven to skip installing the package to the local AEM instance.
**Fixes Applied:**
* Added the `autoInstallSinglePackage` profile to `all/pom.xml`.
* Used the modern `wcmio-content-package-maven-plugin` (version `2.1.6`) instead of the legacy Adobe plugin to ensure compatibility with modern `.zip` metadata formats.

**Issue:** Took over the responsibility of deploying the Java code from `ui.apps`.
**Fixes Applied:**
* Added the `assessment.core` dependency to the main `<dependencies>` list.
* Added an `<embedded>` block for `assessment.core` inside the `filevault-package-maven-plugin` configuration, targeting `/apps/assessment-packages/application/install`.

### 3. `ui.content` Module

**Issue:** Pages rendered as "No content" because AEM could not locate the `jcr:content` nodes. The source code folders were incorrectly named `jcr_content`.
**Fixes Applied:**
* Renamed all instances of `jcr_content` in the source folders to `_jcr_content`.
* **Reasoning:** FileVault requires a leading underscore (`_jcr_content`) in the local file system to correctly translate the folder name to `jcr:content` (with a colon) upon deployment to the AEM JCR.
* Fixed editable template and policy
