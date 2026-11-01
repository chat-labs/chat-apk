import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// chat's own minimum: it starts programs with posix_spawn.
val androidApi = 28
val abiList = providers.gradleProperty("chat.abis").get().split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "moe.vin.chat"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "moe.vin.chat"
        minSdk = androidApi
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abiList }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local builds for now: the same test key as debug builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // chat, tor and lyrebird are programs, which only run from a file on disk: Android only
        // extracts native libraries, and only lib*.so ones.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.bouncycastle)
}

// One chat build at a time: every variant's task shares the same CMake build folders.
abstract class NativeBuildLock : BuildService<BuildServiceParameters.None>

val nativeLock = gradle.sharedServices.registerIfAbsent("chatNativeLock", NativeBuildLock::class) {
    maxParallelUsages.set(1)
}

// chat, built from its source with the NDK for each ABI, as lib/<abi>/libchat.so.
abstract class ChatNative : DefaultTask() {
    @get:Internal abstract val sourceDir: DirectoryProperty
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val api: Property<Int>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources: FileTree
        get() = sourceDir.asFileTree.matching {
            include("src/**", "cmake/**", "CMakeLists.txt", "CHANGELOG.md", "keys/*.pub")
        }

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        val toolchain = ndkDir.get().file("build/cmake/android.toolchain.cmake").asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (abi in abis.get()) {
            val work = workDir.get().dir(abi).asFile
            exec.exec {
                commandLine("cmake", "-S", src, "-B", work, "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-${api.get()}", "-DCMAKE_BUILD_TYPE=Release")
            }
            // Each of chat's dependencies builds with a compiler per CPU itself, so few at once here.
            exec.exec { commandLine("cmake", "--build", work, "-j", minOf(8, Runtime.getRuntime().availableProcessors())) }
            File(work, "chat").copyTo(File(out, "$abi/libchat.so"), overwrite = true)
        }
    }
}

// tor and lyrebird from Tor Project's Android expert bundle, pinned by SHA-256 (the hashes are the
// ones in Tor Browser 16.0's sha256sums-signed-build.txt, whose signature checked out), as
// lib/<abi>/libtor.so and liblyrebird.so, and its pt_config.json (the built-in bridges) as an asset.
abstract class TorBundle : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val bundles: MapProperty<String, String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val jniDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    private fun sha256(f: File): String = f.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun fetch() {
        val jni = jniDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        for ((abi, spec) in bundles.get()) {
            val (arch, sha) = spec.split(":")
            val name = "tor-expert-bundle-android-$arch-${version.get()}.tar.gz"
            val tgz = File(cache, name)
            if (!tgz.isFile || sha256(tgz) != sha) {
                val part = File(cache, "$name.part")
                URI("https://dist.torproject.org/torbrowser/${version.get()}/$name").toURL().openStream().use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                part.renameTo(tgz)
            }
            check(sha256(tgz) == sha) { "$name doesn't match its pinned SHA-256" }
            val unpacked = File(temporaryDir, abi).apply { deleteRecursively(); mkdirs() }
            exec.exec { commandLine("tar", "-xzf", tgz, "-C", unpacked) }
            File(unpacked, "tor/libTor.so").copyTo(File(jni, "$abi/libtor.so"), overwrite = true)
            File(unpacked, "tor/pluggable_transports/lyrebird").copyTo(File(jni, "$abi/liblyrebird.so"), overwrite = true)
            File(unpacked, "tor/pluggable_transports/pt_config.json").copyTo(File(assets, "pt_config.json"), overwrite = true)
        }
    }
}

val torBundles = mapOf(
    "arm64-v8a" to "aarch64:bf8f4c63652fd7daa62288713c7fa512f244a5b972808f6251450e5fdfca9120",
    "x86_64" to "x86_64:ab3152ba164bcd49e50175086c62e40ffa2bf9002a4f6473f77111bd88b0462b",
)

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val chat = tasks.register<ChatNative>("chatNative$cap") {
            sourceDir.set(File(rootDir, providers.gradleProperty("chat.source").get()))
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
            workDir.set(layout.buildDirectory.dir("chat-native"))
            abis.set(abiList)
            api.set(androidApi)
            usesService(nativeLock)
        }
        val tor = tasks.register<TorBundle>("torBundle$cap") {
            version.set("16.0")
            bundles.set(torBundles.filterKeys { it in abiList })
            cacheDir.set(layout.buildDirectory.dir("tor-cache"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(chat, ChatNative::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(tor, TorBundle::jniDir)
        variant.sources.assets?.addGeneratedSourceDirectory(tor, TorBundle::assetsDir)
    }
}
