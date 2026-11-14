import org.gradle.api.tasks.Delete

val miniichatProjectName = "miniichat-main"

// 构建产物留在仓库内：根项目 build/，模块 app/build，CMake 中间产物 app/.cxx

tasks.register("printBuildDirs") {
    doLast {
        println("$miniichatProjectName: projectDir -> ${rootProject.projectDir}")
        println("$miniichatProjectName: ROOT buildDir -> ${layout.buildDirectory.get().asFile}")
        subprojects.forEach { p ->
            println("$miniichatProjectName: ${p.path} buildDir -> ${p.layout.buildDirectory.get().asFile}")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
    delete(file("${rootProject.projectDir}/build"))
    delete(file("${rootProject.projectDir}/app/build"))
    delete(file("${rootProject.projectDir}/app/.cxx"))
    delete(file("${rootProject.projectDir}/build-outputs"))
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
