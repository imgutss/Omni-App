package com.v2ray.ang.ui.base

import android.os.Bundle
import androidx.activity.viewModels
import com.v2ray.ang.ui.settings.AppearanceViewModel
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.ui.compose.AppTheme

abstract class BaseComponentActivity : AppCompatActivity() {

    protected val appearanceViewModel: AppearanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocaleManager.onActivityCreated(this)
        appearanceViewModel
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ScreenContent()
            }
        }
    }

    @Composable
    protected abstract fun ScreenContent()
}