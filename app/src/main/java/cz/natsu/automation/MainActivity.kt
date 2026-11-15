package cz.natsu.automation

import cz.natsu.automation.i18n.tr

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import cz.natsu.automation.data.AutomationTransfer
import cz.natsu.automation.data.ImportException
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.natsu.automation.ui.editor.EditorScreen
import cz.natsu.automation.ui.editor.EditorViewModel
import cz.natsu.automation.trigger.ClipboardMonitor
import cz.natsu.automation.ui.list.AutomationListScreen
import cz.natsu.automation.ui.settings.AppSettingsScreen
import cz.natsu.automation.ui.theme.AutomationTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_AUTOMATION = "cz.natsu.automation.OPEN_AUTOMATION"
        const val EXTRA_OPEN_SETTINGS = "cz.natsu.automation.OPEN_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val openId = intent.getLongExtra(EXTRA_OPEN_AUTOMATION, -1L)
        if (savedInstanceState == null) handleImport(intent)

        setContent {
            AutomationTheme {
                val navController = rememberNavController()
                val application = LocalContext.current.applicationContext as Application

                NavHost(
                    navController = navController,
                    startDestination = when {
                        savedInstanceState != null -> "list"
                        openId >= 0 -> "editor/$openId"
                        intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) -> "settings"
                        else -> "list"
                    },
                    enterTransition = { slideInHorizontally { it / 3 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 3 } + fadeOut() },
                ) {
                    composable("list") {
                        AutomationListScreen(
                            viewModel = viewModel(),
                            onOpen = { id -> navController.navigate("editor/$id") },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        AppSettingsScreen(onBack = {
                            if (!navController.popBackStack()) {
                                navController.navigate("list") { popUpTo(0) }
                            }
                        })
                    }
                    composable(
                        "editor/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType })
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: -1L
                        val editorViewModel: EditorViewModel = viewModel(
                            factory = viewModelFactory { initializer { EditorViewModel(application, id) } }
                        )
                        EditorScreen(editorViewModel, onBack = {
                            if (!navController.popBackStack()) {
                                navController.navigate("list") { popUpTo(0) }
                            }
                        })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // CPU used until now was background use; from now on the app is on screen.
        cz.natsu.automation.trigger.UsageMeter.sample(this, uiVisible = true)
        // Starting the monitor from the foreground also lets Android ask for log access.
        ClipboardMonitor.sync(this, fromForeground = true)
    }

    override fun onPause() {
        cz.natsu.automation.trigger.UsageMeter.sample(this, uiVisible = false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImport(intent)
    }

    /** An export file shared to or opened with the app is imported as new automations. */
    private fun handleImport(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        val app = application as AutomationApplication
        lifecycleScope.launch {
            val message = try {
                val count = AutomationTransfer.import(this@MainActivity, app.repository, uri)
                AutomationTransfer.importedMessage(count)
            } catch (e: ImportException) {
                e.message
            } catch (e: Exception) {
                tr("Import se nepodařil: %s", e.message)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}
