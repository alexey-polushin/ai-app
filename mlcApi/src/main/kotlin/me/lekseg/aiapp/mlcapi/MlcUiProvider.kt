package me.lekseg.aiapp.mlcapi

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

fun interface MlcUiProvider {
    @Composable
    fun Chat(modifier: Modifier)
}
