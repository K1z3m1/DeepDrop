package com.firstt175.novaframe.ui.screens
import com.firstt175.novaframe.ui.refreshConfigState

import com.firstt175.novaframe.ui.Routes

import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaSecondaryButton
import com.firstt175.novaframe.ui.components.NovaTopBar
import com.firstt175.novaframe.ui.theme.NovaPrimary
import com.firstt175.novaframe.ui.theme.NovaStatusWarn

@Composable
fun LegalScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { NovaPreferences(ctx) }
    val bodyHtml = stringResource(R.string.legal_warning_body)
    val plain = remember(bodyHtml) {
        Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        NovaTopBar(
            title = stringResource(R.string.legal_title),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                IconBadge(icon = Icons.Filled.Gavel, tint = NovaStatusWarn, size = 56.dp)
            }
            NovaCard(accent = true) {
                Text(
                    text = "สำคัญ",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(8.dp))
        }

        Button(
            onClick = {
                prefs.setLegalAccepted(true)
                refreshConfigState(prefs)
                nav.navigate(Routes.DLL) {
                    popUpTo(Routes.HOME) { inclusive = false }
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = NovaPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.legal_accept))
        }
        Spacer(Modifier.height(8.dp))
        NovaSecondaryButton(
            text = stringResource(R.string.legal_decline),
            onClick = { nav.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
