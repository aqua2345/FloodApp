package com.example.floodapptest

import android.content.Context
import android.content.SharedPreferences

object OnboardingPreferences {
    private const val PREFS_NAME = "onboarding_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context) {
        getPreferences(context)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    // Метод для сброса онбординга (полезно для тестирования)
    fun resetOnboarding(context: Context) {
        getPreferences(context)
            .edit()
            .putBoolean(KEY_COMPLETED, false)
            .apply()
    }
}
