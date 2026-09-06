import org.gradle.api.tasks.Delete

val miniichatProjectName = "miniichat-main"
val projectOnSharedStorage = rootProject.projectDir.absolutePath.startsWith("/storage/")
val homeDir = System.getenv("HOME")
val explicitBuildRoot = System.getenv("CARTER_BUILD_ROOT") ?: System.getenv("ANDROIDIDE_BUILD_ROOT")
val redirectedBuildRootPath = explicitBuildRoot
    ?: (homeDir?.let { "$it/.androidide-build/$miniichatProjectName" }
        ?: "/data/local/tmp/androidide-build/$miniichatProjectName")
val effectiveRootBuildDir = if (projectOnSharedStorage) {
    file(redirectedBuildRootPath)
} else {
    file("${rootProject.projectDir}/build")
}

layout.buildDirectory.set(effectiveRootBuildDir)

subprojects {
    val modulePath = path.substring(1).replace(":", "/")
    layout.buildDirectory.set(file("$effectiveRootBuildDir/$modulePath"))
}

tasks.register("printBuildDirs") {
    doLast {
        println("$miniichatProjectName: projectDir -> ${rootProject.projectDir}")
        println("$miniichatProjectName: shared storage project -> $projectOnSharedStorage")
        println("$miniichatProjectName: ROOT buildDir -> ${layout.buildDirectory.get().asFile}")
        subprojects.forEach { p ->
            println("$miniichatProjectName: ${p.path} buildDir -> ${p.layout.buildDirectory.get().asFile}")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
    delete(file("${rootProject.projectDir}/build-outputs"))
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
