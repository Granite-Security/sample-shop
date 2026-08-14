plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.11.5"
}

group = "org.granite-security"
version = "0.0.1-SNAPSHOT"
description = "shop"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // shop is now also an OAuth2 *client*: it mints an internal-scope token to
    // ask payment whether a user's orders ever moved money (purge eligibility).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.1") //3.0.3
    implementation("org.springframework.kafka:spring-kafka")

    compileOnly("org.projectlombok:lombok")
//    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.security:spring-security-test")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.named("processTestAot") {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}
