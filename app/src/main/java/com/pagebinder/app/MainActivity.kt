package com.pagebinder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagebinder.app.data.createConsentRepository
import com.pagebinder.app.ui.PageBinderApp
import com.pagebinder.app.ui.consent.ConsentViewModel
import com.pagebinder.app.ui.theme.PageBinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageBinderTheme {
                PageBinderRoot()
            }
        }
    }
}

@Composable
private fun PageBinderRoot() {
    val context = LocalContext.current
    val repository = remember(context) { createConsentRepository(context) }
    val viewModel: ConsentViewModel = viewModel(factory = ConsentViewModel.factory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PageBinderApp(
        uiState = uiState,
        onAgree = viewModel::onAgree,
        onDecline = viewModel::onDecline,
    )
}
