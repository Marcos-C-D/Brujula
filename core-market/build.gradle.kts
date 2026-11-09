plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

dependencies {
    api(project(":core-model"))
    api(project(":core-engine")) // reutiliza el JSON sin dependencias
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
