import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.aelqsimi.ast"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://download.jetbrains.com/teamcity-repository") {
        content {
            includeModule("org.jetbrains.teamcity", "serviceMessages")
        }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val integrationTestImplementation = configurations.getByName("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

val pluginVerifierIdeVersion = providers.gradleProperty("pluginVerifierIdeVersion")

dependencies {
    testImplementation("junit:junit:4.13.2")
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    integrationTestImplementation("org.jetbrains.teamcity:serviceMessages:2024.12")

    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testBundledPlugin("JUnit")
        testFrameworks(
            TestFrameworkType.Platform,
            TestFrameworkType.Plugin.Java,
            TestFrameworkType.Plugin.Kotlin,
        )
        testFramework(
            TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation",
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(
            providers.environmentVariable("PUBLISH_CHANNEL")
                .map { listOf(it) }
                .orElse(listOf("default")),
        )
    }

    pluginVerification {
        ides {
            if (pluginVerifierIdeVersion.isPresent) {
                create(IntelliJPlatformType.IntellijIdea, pluginVerifierIdeVersion.get())
            } else {
                create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
                // 2026.3 is currently an EAP release and must be addressed by its build number.
                create(IntelliJPlatformType.IntellijIdea, "263.4732.28")
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
    jvmToolchain(25)
}

val prepareIntegrationTestProject = tasks.register<Sync>("prepareIntegrationTestProject") {
    from(layout.projectDirectory.dir("src/integrationTest/resources/projects/sample"))
    into(layout.buildDirectory.dir("integration-test-project"))
}

val integrationTest = intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        dependsOn(tasks.buildPlugin, prepareIntegrationTestProject)
        systemProperty(
            "path.to.build.plugin",
            tasks.buildPlugin.flatMap { it.archiveFile }.get().asFile.absolutePath,
        )
        systemProperty(
            "ast.lens.integration.project",
            layout.buildDirectory.dir("integration-test-project").get().asFile.absolutePath,
        )
        if (System.getProperty("os.name").startsWith("Windows")) {
            systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
        }
    }
}

val performanceTest = intellijPlatformTesting.testIde.register("performanceTest") {
    testFrameworks(
        TestFrameworkType.Platform,
        TestFrameworkType.Plugin.Java,
        TestFrameworkType.Plugin.Kotlin,
    )

    task {
        description = "Runs the AST Lens cache performance scenarios."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath += sourceSets.test.get().runtimeClasspath
        include("**/*PerformanceTest.class")
        maxHeapSize = "2g"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        outputs.upToDateWhen { false }
        systemProperty(
            "ast.lens.performance.reportDir",
            layout.buildDirectory.dir("reports/ast-lens-performance").get().asFile.absolutePath,
        )
        listOf(
            "ast.lens.performance.maxColdMs",
            "ast.lens.performance.maxWarmMs",
            "ast.lens.performance.maxEditMs",
        ).forEach { propertyName ->
            providers.systemProperty(propertyName).orNull?.let { value ->
                systemProperty(propertyName, value)
            }
        }
    }
}

tasks {
    named("verifyPluginSignature") {
        dependsOn("signPlugin")
    }

    named<VerifyPluginTask>("verifyPlugin") {
        if (providers.systemProperty("os.name").get().startsWith("Windows", ignoreCase = true)) {
            systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
        }
    }

    test {
        exclude("**/*PerformanceTest*.class")
    }

    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }

    wrapper {
        gradleVersion = "9.8.0"
    }
}
