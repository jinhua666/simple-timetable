pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional cache populated from the official Google Maven repository.
        // Useful on Windows networks where the JDK HTTPS client intermittently fails.
        if (file(".tools/maven").exists()) maven {
            url = uri(".tools/maven")
            metadataSources { gradleMetadata(); mavenPom() }
        }
        // Google Maven mirror for reliable AndroidX downloads on this network.
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content { includeGroupByRegex("androidx\\..*"); includeGroup("com.google.testing.platform") }
        }
        mavenCentral()
        google()
    }
}
rootProject.name = "SimpleTimetable"
include(":app")
