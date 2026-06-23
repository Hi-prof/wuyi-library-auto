package com.wuyi.libraryauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.wuyi.libraryauto.ui.navigation.AppNavGraph
import com.wuyi.libraryauto.ui.theme.WuyiLibraryTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WuyiLibraryTheme {
                AppNavGraph()
            }
        }
    }
}
