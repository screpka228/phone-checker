package com.phonechecker

import android.app.Application
import com.phonechecker.data.AppState

class App : Application() {
    val state by lazy { AppState(this) }
}
