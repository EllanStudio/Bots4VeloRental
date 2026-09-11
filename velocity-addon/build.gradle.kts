plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly("dev.nulli0n.bots4velo:bots4velo-addon-api:3.0.8")
    implementation(project(":common"))
    implementation("org.yaml:snakeyaml:2.5")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")

    testImplementation("dev.nulli0n.bots4velo:bots4velo-addon-api:3.0.8")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("bot-rental-version.properties") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("bots4velo-rental-addon")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    relocate("com.google.gson", "dev.ellan.botrental.velocity.lib.gson")
    relocate("org.yaml.snakeyaml", "dev.ellan.botrental.velocity.lib.snakeyaml")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
