import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

group = "com.prompthub"
version = "0.0.1-SNAPSHOT"

extra["springCloudVersion"] = "2025.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "checkstyle")

    extensions.configure<CheckstyleExtension>("checkstyle") {
        configFile = rootProject.file("style/checkstyle/prompthub-checkstyle-rules.xml")
        toolVersion = "10.12.0"
        isIgnoreFailures = true
    }

    tasks.withType<Checkstyle>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    plugins.withId("io.spring.dependency-management") {
        extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>("dependencyManagement") {
            imports {
                mavenBom("org.springframework.cloud:spring-cloud-dependencies:${rootProject.extra["springCloudVersion"]}")
            }
        }
    }

    tasks.matching { it.name == "checkstyleTest" }.configureEach {
        enabled = false
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}
