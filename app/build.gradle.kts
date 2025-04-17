


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // اضافه کردن پلاگین Kapt برای پردازش Annotationهای Room
    id("kotlin-kapt")
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
    implementation("androidx.multidex:multidex:2.0.1")
    // --- Core & UI ---
    implementation("androidx.core:core-ktx:1.16.0") // نسخه به‌روز شده طبق درخواست
    implementation("androidx.appcompat:appcompat:1.7.0") // یا آخرین نسخه پایدار
    implementation("com.google.android.material:material:1.12.0") // یا آخرین نسخه پایدار
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // یا آخرین نسخه پایدار

    // --- Lifecycle (ViewModel & Coroutine Scopes) ---
    val lifecycleVersion = "2.8.0" // یا آخرین نسخه پایدار
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    // implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion") // اگر از LiveData استفاده می‌کنید

    // --- Room (Database) ---
    val roomVersion = "2.6.1" // یا آخرین نسخه پایدار
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion") // For Kotlin Coroutines support
    kapt("androidx.room:room-compiler:$roomVersion") // Annotation processor

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0") // یا آخرین نسخه پایدار
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0") // یا آخرین نسخه پایدار

    // --- Networking (TCP/IP Communication is likely handled by standard Java/Kotlin sockets, no specific library needed unless using higher level ones) ---

    // --- Location Services ---
    implementation("com.google.android.gms:play-services-location:21.3.0") // یا آخرین نسخه پایدار

    // --- Graph Library (Example: MPAndroidChart) ---
    // دستورالعمل گراف خواسته بود، این یک کتابخانه محبوب است
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0") // برای استفاده، jitpack را به settings.gradle اضافه کنید

    // --- Excel Exporter Library (Example: Apache POI) ---
    // دستورالعمل خروجی اکسل خواسته بود
    // نکته: POI کتابخانه بزرگی است و ممکن است نیاز به تنظیمات Proguard/Multidex داشته باشد
  //  implementation("org.apache.poi:poi:5.3.0")
 //   implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.poi:poi-ooxml-lite:5.3.0")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4") // یا آخرین نسخه موجود
}

// اضافه کردن ریپازیتوری JitPack اگر از کتابخانه‌هایی مثل MPAndroidChart استفاده می‌کنید
// این باید در فایل settings.gradle.kts اضافه شود، نه اینجا.
// dependencyResolutionManagement {
//     repositories {
//         // ... google(), mavenCentral() ...
//         maven { url = uri("https://jitpack.io") }
//     }
// }

