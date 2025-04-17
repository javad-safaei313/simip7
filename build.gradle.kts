// Top-level build file where you can add configuration options common to all sub-projects/modules.
//plugins {
//    alias(libs.plugins.android.application) apply false
 //   alias(libs.plugins.kotlin.android) apply false
//}


// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // استفاده از آخرین نسخه پایدار پلاگین اندروید (تا تاریخ آپریل ۲۰۲۵)
        // نسخه 8.x.x از Desugaring به شکل بهتری پشتیبانی می‌کند
        classpath("com.android.tools.build:gradle:8.9.0") // یا آخرین نسخه پایدار موجود

        // استفاده از نسخه کاتلین سازگار با پلاگین اندروید
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23") // یا آخرین نسخه پایدار موجود

        // اگر از Hilt استفاده می‌کردید (در کد پیشنهادی قبلی نبود، اما برای تکمیل می‌آورم):
        // val hiltVersion = "2.51.1" // یا آخرین نسخه پایدار
        // classpath("com.google.dagger:hilt-android-gradle-plugin:$hiltVersion")

        // اگر از Navigation Safe Args استفاده می‌کردید:
        // val navVersion = "2.7.7" // یا آخرین نسخه پایدار
        // classpath("androidx.navigation:navigation-safe-args-gradle-plugin:$navVersion")

    }
}

// در نسخه‌های جدید Gradle، تعریف پلاگین‌ها در سطح بالا با apply false رایج‌تر است
// و سپس در ماژول‌ها apply می‌شوند.
// اگر فایل شما از این روش استفاده می‌کند، پلاگین‌های زیر را اینجا اضافه کنید:
plugins {
    // پلاگین اصلی اندروید برای ماژول اپلیکیشن
    id("com.android.application") version "8.9.0" apply false
    // پلاگین اصلی کاتلین اندروید
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    // پلاگین Kapt (اگر هنوز از Annotation Processing قدیمی استفاده می‌کنید)
    id("org.jetbrains.kotlin.kapt") version "1.9.23" apply false // نسخه باید با kotlin یکی باشد
    // پلاگین KSP (اگر از KSP برای Room یا ... استفاده می‌کنید، جایگزین Kapt)
    // id("com.google.devtools.ksp") version "1.9.23-1.0.19" apply false
    // پلاگین Hilt (اگر استفاده می‌کنید)
    // id("com.google.dagger.hilt.android") version "2.51.1" apply false
}


// حذف بلوک allprojects - تعریف ریپازیتوری‌ها به settings.gradle.kts منتقل شده
// allprojects {
//     repositories {
//         google()
//         mavenCentral()
//         // maven { url = uri("https://jitpack.io") } // این به settings.gradle.kts منتقل شود
//     }
// }


tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}