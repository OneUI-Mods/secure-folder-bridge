import java.util.Properties

plugins { id("com.android.application") }
val signingProperties = Properties().apply {
    providers.environmentVariable("SFB_SIGNING_PROPERTIES").orNull?.let {
        file(it).reader(Charsets.UTF_8).use { reader -> load(reader) }
    }
}
android {
    namespace = "dev.ryuya.securefolderbridge"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.ryuya.securefolderbridge"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (!signingProperties.isEmpty) create("distribution") {
            storeFile = file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { abortOnError = true }
}
dependencies { implementation("com.google.android.material:material:1.14.0") }
val nativeOutput = layout.buildDirectory.dir("generated/nativeLibs")
android.sourceSets.getByName("main").jniLibs.directories.add(nativeOutput.get().asFile.absolutePath)
val buildNative = tasks.register<Exec>("buildNative") {
    inputs.file(rootProject.file("native/mount_bridge.c"))
    outputs.dir(nativeOutput)
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val output = nativeOutput.get().asFile.resolve("arm64-v8a")
        output.mkdirs()
        val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        commandLine(
            sdk.resolve("ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android31-clang"),
            "-O2", "-Wall", "-Wextra", "-Werror", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
            "-Wl,-z,relro,-z,now", rootProject.file("native/mount_bridge.c"),
            "-o", output.resolve("libfolder_mount.so"),
        )
    }
}
tasks.named("preBuild").configure { dependsOn(buildNative) }
