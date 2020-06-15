import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

import java.time.Duration

plugins {
    kotlin("jvm") version "1.3.72"
}

allprojects {
    repositories {
        mavenCentral()
        jcenter()
    }

    extra.apply {
        set("junitVersion", "5.6.1")
        set("hamkrestVersion", "1.7.0.3")
        set("guiceVersion", "4.2.3")
        set("kotlinGuiceVersion", "1.4.1")
        set("mockkVersion", "1.9.3")
        set("externalLibraryVersion", "1.2.1")
    }

    dependencies {
        val dokkaVersion: String? by extra
    }
}

subprojects {
    apply(plugin = "kotlin")

    val junitVersion: String? by extra

    dependencies {
        implementation ("com.google.code.gson:gson:2.8.6")
        implementation ("org.jetbrains.dokka", "dokka-gradle-plugin", "0.10.1")
        implementation(kotlin("stdlib-jdk8"))
        implementation(kotlin("reflect"))

        testImplementation("org.junit.jupiter", "junit-jupiter-engine", junitVersion)
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Make sure tests don't take over 10 minutes
        timeout.set(Duration.ofMinutes(10))

        minHeapSize = "512M"
        maxHeapSize = "2048M"
    }
}

task<Zip>("submission") {
    val taskname = "submission"
    val base = project.rootDir.name
    archiveBaseName.set(taskname)
    from(project.rootDir.parentFile) {
        include("$base/**")
        exclude("$base/**/*.iml", "$base/*/build", "$base/**/.gradle", "$base/**/.idea", "$base/*/out",
                "$base/**/.git", "$base/**/.DS_Store", "$base/build", "$base/out")
        exclude("$base/$taskname.zip")
    }
    destinationDirectory.set(project.rootDir)
}