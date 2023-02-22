plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.6.10-1.0.2")
    implementation("com.squareup:kotlinpoet-ksp:1.10.2")
}