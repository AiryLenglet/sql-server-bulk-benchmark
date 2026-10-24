plugins {
    java
    application
}

group = "ch.lenglet"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.sqlserver:mssql-jdbc:13.6.0.jre11")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

application {
    mainClass = "ch.lenglet.bulkbench.BulkBench"
    // Fixed heap so heap resizing doesn't add noise between runs
    applicationDefaultJvmArgs = listOf("-Xms2g", "-Xmx2g")
}
