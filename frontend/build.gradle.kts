// Top-level build file where you can add configuration options common to all sub-projects/modules.
//plugins {
    //alias(libs.plugins.android.application) apply false
    //alias(libs.plugins.kotlin.compose) apply false
//}

plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}