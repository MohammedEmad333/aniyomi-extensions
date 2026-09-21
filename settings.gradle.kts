apply(from = "repositories.gradle.kts")

include(":core")

if (System.getenv("CI") != "true") {
    loadAllExtensions()
} else {
    loadAllExtensions()
}

fun loadAllExtensions() {
    val src = File(rootDir, "src")
    if (!src.exists()) return
    src.listFiles()?.filter { it.isDirectory }?.forEach { lang ->
        lang.listFiles()?.filter { it.isDirectory }?.forEach { ext ->
            include("src:${lang.name}:${ext.name}")
        }
    }
}
