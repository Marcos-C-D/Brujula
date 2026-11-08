plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
