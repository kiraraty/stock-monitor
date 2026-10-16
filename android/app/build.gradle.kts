plugins { id("com.android.application") }

android {
    namespace = "app.pnale.ticker"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pnale.ticker"
        minSdk = 36
        targetSdk = 36
        versionCode = 14
        versionName = "0.14.0"
        testInstrumentationRunner = "app.pnale.ticker.MarketSmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
