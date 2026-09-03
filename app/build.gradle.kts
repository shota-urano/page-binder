import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

val releaseSigningProperties =
    Properties().apply {
        val propertiesFile = rootProject.file("keystore.properties")
        if (propertiesFile.isFile) {
            propertiesFile.inputStream().use(::load)
        }
    }

fun releaseSigningValue(
    propertyName: String,
    environmentName: String,
): String? =
    releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSigningValue("storeFile", "PAGEBINDER_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "PAGEBINDER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "PAGEBINDER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "PAGEBINDER_RELEASE_KEY_PASSWORD")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

abstract class VerifyMergedManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val documentBuilderFactory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        val document = documentBuilderFactory.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val forbiddenPermissions =
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
            )
        val violations = mutableListOf<String>()

        val permissionElementNames = setOf("uses-permission", "uses-permission-sdk-23")
        for (elementName in permissionElementNames) {
            val permissionElements = document.getElementsByTagName(elementName)
            for (index in 0 until permissionElements.length) {
                val permission =
                    permissionElements
                        .item(index)
                        .attributes
                        .getNamedItemNS(androidNamespace, "name")
                        ?.nodeValue
                if (permission in forbiddenPermissions) {
                    violations += "forbidden permission: $permission"
                }
            }
        }

        val serviceElements = document.getElementsByTagName("service")
        for (index in 0 until serviceElements.length) {
            val service = serviceElements.item(index)
            val serviceName = service.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ?: "<unnamed>"
            val bindPermission = service.attributes.getNamedItemNS(androidNamespace, "permission")?.nodeValue
            var declaresAccessibilityService = bindPermission == "android.permission.BIND_ACCESSIBILITY_SERVICE"

            val descendants = service.childNodes
            for (childIndex in 0 until descendants.length) {
                val child = descendants.item(childIndex)
                if (child.nodeName != "intent-filter") continue
                val intentFilterChildren = child.childNodes
                for (intentIndex in 0 until intentFilterChildren.length) {
                    val intentChild = intentFilterChildren.item(intentIndex)
                    val actionName =
                        intentChild.attributes
                            ?.getNamedItemNS(androidNamespace, "name")
                            ?.nodeValue
                    if (
                        intentChild.nodeName == "action" &&
                        actionName == "android.accessibilityservice.AccessibilityService"
                    ) {
                        declaresAccessibilityService = true
                    }
                }
            }

            if (declaresAccessibilityService) {
                violations += "AccessibilityService declaration: $serviceName"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Merged manifest contains forbidden declarations:\n${violations.joinToString("\n")}",
            )
        }
    }
}

android {
    namespace = "com.pagebinder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pagebinder.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.pagebinder.app.PageBinderTestRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFilePath?.let(rootProject::file)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Validates the external release signing configuration."

    doLast {
        val missingSettings =
            buildList {
                if (releaseStoreFilePath == null) add("storeFile")
                if (releaseStorePassword == null) add("storePassword")
                if (releaseKeyAlias == null) add("keyAlias")
                if (releaseKeyPassword == null) add("keyPassword")
            }
        if (missingSettings.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: ${missingSettings.joinToString()}. " +
                    "Set keystore.properties or PAGEBINDER_RELEASE_* environment variables; " +
                    "see docs/release-signing.md.",
            )
        }

        if (!rootProject.file(releaseStoreFilePath!!).isFile) {
            throw GradleException("Release signing keystore file does not exist; see docs/release-signing.md.")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.hilt.android)
    kapt(libs.androidx.room.compiler)
    kapt(libs.hilt.compiler)
    kaptAndroidTest(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        tasks.register<VerifyMergedManifestTask>("verifyDebugMergedManifest") {
            group = "verification"
            description = "Checks the merged debug manifest for forbidden network and accessibility declarations."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
    }
}
