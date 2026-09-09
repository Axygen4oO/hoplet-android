package com.wdtt.client.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wdtt.client.AppLinks
import com.wdtt.client.R
import com.wdtt.client.ui.AppCardDefaults
import com.wdtt.client.ui.AppSectionCard

private fun openContact(context: Context, configuredUri: String, fallbackUri: String? = null) {
    if (configuredUri.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(configuredUri))
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        if (!fallbackUri.isNullOrBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)))
            } catch (_: Exception) {
                // Нет приложения/браузера — оставляем экран без технической ошибки.
            }
        }
    }
}

@Composable
fun AdminContactCard(context: Context, modifier: Modifier = Modifier) {
    AppSectionCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        color = AppCardDefaults.containerColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Оплата и продление",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Связаться с администратором",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    openContact(context, AppLinks.ADMIN_TELEGRAM, AppLinks.ADMIN_TELEGRAM_WEB)
                },
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(painterResource(R.drawable.ic_telegram), contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Telega", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp), maxLines = 2)
            }
            OutlinedButton(
                onClick = { openContact(context, AppLinks.ADMIN_MAX, AppLinks.ADMIN_MAX_WEB) },
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_max), contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF6B5CE7))
                Text("MAX", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp), maxLines = 2)
            }
            OutlinedButton(
                onClick = {
                    openContact(context, AppLinks.ADMIN_WHATSAPP, AppLinks.ADMIN_WHATSAPP_WEB)
                },
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_whatsapp), contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF25D366))
                Text("WhatsApp", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp), maxLines = 2)
            }
        }
    }
}
