package dev.esxiclient.app.ui.navigation
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.esxiclient.app.ui.screens.home.HomeScreen
import dev.esxiclient.app.ui.screens.login.LoginScreen
import dev.esxiclient.app.ui.screens.settings.SettingsScreen
import dev.esxiclient.app.ui.screens.vmdetail.VmDetailScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val VM_DETAIL = "vm/{vmId}"
    const val SETTINGS = "settings"
    fun vmDetail(vmId: String) = "vm/$vmId"
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoginSuccess = { navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onVmClick = { vmId -> navController.navigate(Routes.vmDetail(vmId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onLogout = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
            )
        }
        composable(route = Routes.VM_DETAIL, arguments = listOf(navArgument("vmId") { type = NavType.StringType })) { backStackEntry ->
            val vmId = backStackEntry.arguments?.getString("vmId") ?: ""
            VmDetailScreen(vmId = vmId, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}