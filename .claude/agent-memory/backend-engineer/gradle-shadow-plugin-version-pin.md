---
name: gradle-shadow-plugin-version-pin
description: com.gradleup.shadow plugin version must match the Gradle version in use, not just be "latest".
metadata:
  type: feedback
---

When adding the Shadow (fat-jar) Gradle plugin, don't default to the latest release without
checking its Gradle compatibility matrix first.

**Why:** shadow 9.3.0+ requires Gradle 9.0+; this project's Gradle wrapper is pinned to 8.13 (to
match the Android app's wrapper — see `gradle/wrapper/gradle-wrapper.properties`). Using shadow
9.5.0 against Gradle 8.13 fails with an opaque
`'void org.gradle.api.component.AdhocComponentWithVariants.addVariantsFromConfiguration(...)'`
error that gives no hint it's a version mismatch. The compatibility matrix is documented in the
shadow README (github.com/GradleUp/shadow): shadow 9.0.0–9.2.2 supports Gradle 8.11+; used 9.2.2
for Never Late's backend.

**How to apply:** whenever adding/bumping the shadow plugin (or likely other Gradle-version-
sensitive plugins) in a project with a pinned older Gradle wrapper, check the plugin's own
compatibility table before picking a version — "latest" is not always compatible.
