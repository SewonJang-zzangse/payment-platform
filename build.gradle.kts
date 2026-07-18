plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.crossremit"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // --- Runtime ---
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Temporal: durable saga orchestration
    implementation("io.temporal:temporal-sdk:1.28.0")

    // Postgres: ledger storage
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")          // connection pool

    // --- Test ---
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // JdbcLedger integration tests against a real Postgres (optional)
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    // In-memory Temporal test server for workflow unit tests
    testImplementation("io.temporal:temporal-testing:1.28.0")
}

application {
    mainClass.set("com.crossremit.payment.transfer.temporal.PaymentWorkerKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
