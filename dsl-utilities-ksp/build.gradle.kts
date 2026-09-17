import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
    signing
}

dependencies {
    implementation(libs.kspApi)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    // Keeps the annotations on the classpath for Dokka KDoc links; the
    // processor references them only by name through KSP.
    compileOnly(project(":dsl-utilities"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // The only intended public surface is the KSP service-loader entry point,
    // which consumers use as a binary dependency through KSP; explicit API
    // mode would not add any guarantee, so it is deliberately not applied.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dokka {
    dokkaSourceSets {
        configureEach {
            sourceLink {
                remoteUrl = uri("https://github.com/xfqwdsj/dsl-utilities/tree/v${version}/${project.name}")
            }
            documentedVisibilities(VisibilityModifier.Public, VisibilityModifier.Protected)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name = project.name
        description = "A Kotlin multiplatform library providing various utilities for Kotlin DSLs."
        url = "https://github.com/xfqwdsj/dsl-utilities"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/license/mit/"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "xfqwdsj"
                name = "LTFan"
                email = "xfqwdsj@qq.com"
                roles = listOf("Author", "Maintainer")
            }
        }

        scm {
            connection = "scm:git:https://github.com/xfqwdsj/dsl-utilities.git"
            developerConnection = "scm:git:https://github.com/xfqwdsj/dsl-utilities.git"
            url = "https://github.com/xfqwdsj/dsl-utilities"
        }
    }

    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka(tasks.dokkaGeneratePublicationHtml),
        )
    )
}

publishing {
    repositories {
        maven {
            name = "gitHubPackages"
            url = uri("https://maven.pkg.github.com/xfqwdsj/dsl-utilities")
            credentials(PasswordCredentials::class)
        }
    }
}

signing {
    sign(publishing.publications)
    val publishSigningMode = findProperty("publishSigningMode") as String?
    if (publishSigningMode == "inMemory") return@signing
    useGpgCmd()
}

group = "top.ltfan.dslutilities"
