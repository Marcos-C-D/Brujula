plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

dependencies {
    api(project(":core-model"))
    api(project(":core-engine"))
    api(project(":core-market"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
