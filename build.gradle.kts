plugins {
    java
}

group = "dev.gokart"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Latest Paper 26.3 API build (26.3 is currently a beta on Paper's side).
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Zip the trophy resource pack and ship it inside the plugin jar.
val resourcePack by tasks.registering(Zip::class) {
    from("resourcepack")
    archiveFileName.set("GoKart-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
}

tasks.processResources {
    dependsOn(resourcePack)
    from(resourcePack)
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

val author = "Redstoneskew"

tasks.jar {
    archiveFileName.set("GoKart-${project.version}.jar")
    manifest {
        attributes(
            "Implementation-Title" to "GoKart",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to author,
            "Built-By" to author,
        )
    }
    doLast {
        println("")
        println("  GoKart ${project.version} by $author")
        println("  -> build/libs/GoKart-${project.version}.jar")
        println("")
    }
}
