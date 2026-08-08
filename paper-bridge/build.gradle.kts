plugins {
    java
    id("com.gradleup.shadow")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":common"))
    implementation("org.yaml:snakeyaml:2.5")

    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("su.nightexpress.excellenteconomy:ExcellentEconomy:2.8.0")
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("bots4velo-rental-paper")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    relocate("com.google.gson", "dev.ellan.botrental.paper.lib.gson")
    relocate("org.yaml.snakeyaml", "dev.ellan.botrental.paper.lib.snakeyaml")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
