plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

dependencies {
    api(project(":core-model"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
