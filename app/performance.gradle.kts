import com.android.build.gradle.AppExtension

// Applied only with -PenablePerformanceHarness=true. Never modifies the shipping release.
configure<AppExtension> {
    buildTypes.create("performance") {
        initWith(buildTypes.getByName("release"))
        matchingFallbacks += "release"
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        applicationIdSuffix = ".performance"
        // Disposable local/CI fixture APK. No release credentials are needed by this variant.
        signingConfig = signingConfigs.getByName("debug")
    }
}
