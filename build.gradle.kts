import java.util.Properties

plugins {
    java
}

group = property("pluginGroup") as String
version = property("pluginVersion") as String

// Where Hytale is installed. Defaults to the Flatpak launcher's folders under your home
// directory; override with -PhytaleDir=... / -PhytaleModsDir=... or in local.properties.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun setting(name: String, default: String): String =
    findProperty(name) as String? ?: localProperties.getProperty(name) ?: default
val hytaleData = System.getProperty("user.home") + "/.var/app/com.hypixel.HytaleLauncher/data/Hytale"

val hytaleDir = file(setting("hytaleDir", "$hytaleData/install/release/package/game/latest"))
val serverJar = hytaleDir.resolve("Server/HytaleServer.jar")
val hytaleModsDir = file(setting("hytaleModsDir", "$hytaleData/UserData/Mods"))

repositories {
    mavenCentral()
}

dependencies {
    // The server jar is provided at runtime by Hytale itself (it also bundles Gson).
    compileOnly(files(serverJar))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // HytaleServer.jar is built for Java 25; any JDK >= 25 can compile with --release 25.
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("manifest.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("ObjectiveTracker")
}

// Copies the built jar into the Hytale client's Mods folder.
val installMod by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Builds the plugin and copies it into the Hytale UserData/Mods folder."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(hytaleModsDir)
}

// Runs a local dedicated server from ./run with this plugin loaded.
val runServer by tasks.registering(JavaExec::class) {
    group = "hytale"
    description = "Starts a local Hytale server in ./run with this plugin installed."
    dependsOn(tasks.jar)
    val runDir = layout.projectDirectory.dir("run").asFile
    doFirst {
        runDir.resolve("mods").mkdirs()
        copy {
            from(tasks.jar.flatMap { it.archiveFile })
            into(runDir.resolve("mods"))
        }
    }
    workingDir = runDir
    classpath = files(serverJar)
    mainClass.set("com.hypixel.hytale.Main")
    args("--assets", hytaleDir.resolve("Assets.zip").absolutePath, "--disable-sentry")
    standardInput = System.`in`
}
