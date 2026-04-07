package me.lekseg.aiapp.mlcext

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.lekseg.aiapp.mlcapi.MlcUiProvider

class MlcChatProvider : MlcUiProvider {
    @Composable
    override fun Chat(modifier: Modifier) {
        MlcChatScreen(modifier)
    }
}
