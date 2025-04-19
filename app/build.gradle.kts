


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // اضافه کردن پلاگین Kapt برای پردازش Annotationهای Room
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "com.simip" // مطمئن شو با پکیج اصلی پروژه یکی باشه
    compileSdk = 35 // استفاده از SDK جدیدتر توصیه می‌شه

    defaultConfig {
        applicationId = "com.simip"
        minSdk = 26 // طبق دستورالعمل
        targetSdk = 35 // استفاده از SDK جدیدتر توصیه می‌شه
        versionCode = 1
        versionName = "1.2" // طبق دستورالعمل نسخه ۱.۲
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // فعلا برای دیباگ راحت‌تره
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // فعال کردن ViewBinding برای دسترسی راحت‌تر به Viewها در کد
    buildFeatures {
        viewBinding = true
    }
    // اگر از Compose استفاده نمی‌کنید، این بلوک ضروری نیست
    // composeOptions {
    //     kotlinCompilerExtensionVersion = "..." // specify Compose compiler version if using Compose
    // }
    packaging {
        // اگر با کتابخانه‌هایی مثل Apache POI به مشکل لایسنس برخوردید
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {

    // ** توجه: نسخه‌ها عمداً به نسخه‌های پایدار قدیمی‌تر برگردانده شده‌اند برای رفع مشکل Build **

    // --- Desugaring (اگر minSdk < 26 است، نگه دارید) ---
    // نسخه 1.2.2 برای مدت زیادی پایدار بود قبل از 2.x
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.2.2")

    // --- Core & UI ---
    implementation("androidx.core:core-ktx:1.12.0") // عقب‌تر از 1.16.0
    implementation("androidx.appcompat:appcompat:1.6.1") // عقب‌تر از 1.7.0
    implementation("com.google.android.material:material:1.10.0") // عقب‌تر از 1.12.0
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // این نسخه پایدار قدیمی و رایج است

    // --- Lifecycle (ViewModel & Coroutine Scopes) ---
    val lifecycleVersion = "2.6.2" // عقب‌تر از 2.8.0
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // --- Room (Database) ---
    val roomVersion = "2.5.2" // عقب‌تر از 2.6.1
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") // عقب‌تر از 1.8.0
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") // عقب‌تر از 1.8.0

    // --- Location Services ---
    implementation("com.google.android.gms:play-services-location:21.1.0") // عقب‌تر از 21.3.0

    // --- Graph Library (MPAndroidChart) ---
    // نسخه v3.1.0 برای مدت زیادی پایدار بوده است
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // --- Excel Exporter Library (Apache POI) ---
    // برگرداندن به یک نسخه پایدار کمی قدیمی‌تر
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5") // یا از poi-ooxml-lite:5.2.5 استفاده کنید اگر lite کافی بود

    // --- Testing ---
    testImplementation("junit:junit:4.13.2") // نسخه‌های استاندارد تست معمولاً نیازی به تغییر ندارند
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // --- MultiDex (اگر minSdk < 26 است و نیاز بود، نگه دارید) ---
    // implementation("androidx.multidex:multidex:2.0.1")
}

