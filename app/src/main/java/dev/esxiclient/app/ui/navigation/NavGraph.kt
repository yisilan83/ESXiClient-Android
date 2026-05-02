package dev.esxiclient.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.esxiclient.app.ui.screens.home.HomeScreen
import dev.esxiclient.app.ui.screens.login.LoginScreen
import dev.esxiclient.app.ui.screens.settings.SettingsScreen
import dev.esxiclient.app.ui.screens.vmdetail.VmDetailScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val VM_DETAIL = "vm_detail/{vmId}"
    const val SETTINGS = "settings"

    fun vmDetail(vmId: String) = "vm_detail/$vmId"
}

@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN,
        modifier = modifier
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onVmClick = { vmId -> navController.navigate(Routes.vmDetail(vmId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.VM_DETAIL) { backStackEntry ->
            val vmId = backStackEntry.arguments?.getString("vmId") ?: ""
            VmDetailScreen(
                vmId = vmId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
