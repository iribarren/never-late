package com.neverlate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.neverlate.ui.theme.NeverLateTheme

/**
 * A reusable, **parameterized composable**: instead of writing a near-identical centered
 * icon/message/button `Box` in every screen that needs an empty or error state — as this project
 * used to, with three almost-copy-pasted private composables (`EmptyTasks`, `EmptyArticles`,
 * `ErrorArticles`) — this one composable takes the parts that *vary* as parameters, and every
 * screen supplies its own. This is the Compose equivalent of extracting a small reusable view
 * class in an OOP UI toolkit, but expressed as a function taking data in, not an object holding
 * state.
 *
 * [actionLabel] and [onAction] are an intentional pair: the action [Button] only renders when
 * **both** are non-null. A screen with nothing useful for the user to do here (e.g. an empty
 * article list, which has no "create an article" flow) simply omits both and gets an icon +
 * message with no dangling button, rather than every caller having to invent a no-op lambda.
 *
 * Used by [com.neverlate.ui.tasks.TasksScreen] (empty tasks, with a "create a task" action) and
 * [com.neverlate.ui.articles.ArticlesScreen] (empty articles, no action; failed load, with a
 * retry action).
 */
@Composable
fun MessageState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = icon,
                // Decorative: the message Text right below conveys the same information, so a
                // screen reader announcing this icon too would only repeat itself.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (actionLabel != null && onAction != null) {
                // Feature 18 (US-5): Material 3's Button defaults to a 40dp minimum height, below
                // the 48dp accessibility touch-target guideline — minimumInteractiveComponentSize
                // reserves the extra layout space without changing how the button looks.
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .minimumInteractiveComponentSize(),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageStateWithActionPreview() {
    NeverLateTheme {
        MessageState(
            icon = Icons.Filled.ErrorOutline,
            message = "No se han podido cargar los artículos.",
            actionLabel = "Reintentar",
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageStateWithoutActionPreview() {
    NeverLateTheme {
        MessageState(
            icon = Icons.Filled.ErrorOutline,
            message = "No hay artículos disponibles todavía.",
        )
    }
}
