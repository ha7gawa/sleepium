
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// the version (gtnh.modules.gitVersion = false: no git tags on this branch)
version = "1.4.12.1"
extra["modVersion"] = "1.4.12.1"

// -PextraJvm="-Da=b -Dc=d": JVM arguments for the run tasks (benches)
tasks.withType<JavaExec>().configureEach {
    val extra = project.findProperty("extraJvm") as String?
    if (name.startsWith("run") && extra != null) jvmArgs(extra.split(" ").filter { it.isNotBlank() })
}

// the obfuscated runs start where prepareObfModsFolder puts the mods (the template starts them in run/client, whose mods/ is empty)
tasks.named<JavaExec>("runObfClient") { workingDir = file("run/obfuscated") }
tasks.named<JavaExec>("runObfServer") { workingDir = file("run/obfuscated") }

// -Pobf_extra_mods=a.jar,b.jar: other mods next to ours in the obfuscated runs (compatibility tests, e.g. OptiFine)
tasks.named("prepareObfModsFolder") {
    val extra = project.findProperty("obf_extra_mods") as String?
    val dir = file("run/obfuscated/mods")
    doLast {
        extra?.split(",")?.map { File(it.trim()) }?.filter { it.isFile }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    }
}
