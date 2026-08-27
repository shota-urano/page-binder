import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition.japanese)
    kapt(libs.androidx.room.compiler)
    kaptAndroidTest(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
