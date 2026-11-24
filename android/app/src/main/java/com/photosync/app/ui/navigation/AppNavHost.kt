package com.photosync.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photosync.app.ui.activity.ActivityScreen
import com.photosync.app.ui.auth.LoginScreen
import com.photosync.app.ui.auth.RegisterScreen
import com.photosync.app.ui.home.HomeScreen
import com.photosync.app.ui.onboarding.OnboardingScreen
import com.photosync.app.ui.serverconfig.ServerConfigScreen
import com.photosync.app.ui.spaces.AddMappingScreen
import com.photosync.app.ui.spaces.JoinSpaceScreen
import com.photosync.app.ui.spaces.MappingFilesScreen
import com.photosync.app.ui.spaces.SpaceDetailScreen
import com.photosync.app.ui.spaces.SpaceEditScreen
import com.photosync.app.ui.splash.SplashScreen
import com.photosync.app.ui.trash.TrashScreen
import com.photosync.app.ui.versions.FileVersionsScreen

@Composable
fun AppRoot(navController: NavHostController = rememberNavController()) {

    fun navigateClearing(route: String) {
        navController.navigate(route) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(onNavigate = ::navigateClearing)
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onStartConfig = { navController.navigate(Screen.ServerConfig.route) },
                onHaveAccount = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.ServerConfig.route) {
            ServerConfigScreen(
                onBack = { navController.popBackStack() },
                onSaved = { loggedIn ->
                    navigateClearing(if (loggedIn) Screen.Home.route else Screen.Login.route)
                }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { navigateClearing(Screen.Home.route) },
                onGoRegister = { navController.navigate(Screen.Register.route) },
                onGoServerConfig = { navController.navigate(Screen.ServerConfig.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = { navigateClearing(Screen.Home.route) },
                onGoLogin = { navController.popBackStack() }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                navController = navController,
                onLoggedOut = { navigateClearing(Screen.Login.route) }
            )
        }

        composable(Screen.SpaceCreate.route) {
            SpaceEditScreen(
                spaceId = null,
                onBack = { navController.popBackStack() },
                onSaved = { spaceId, created ->
                    if (created) {
                        // 创建空间后立即绑定本地目录，避免出现没有本地目录的空空间。
                        navController.navigate(Screen.AddMapping(spaceId, firstBinding = true).route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(Screen.SpaceDetail(spaceId).route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(Screen.JoinSpace.route) {
            JoinSpaceScreen(
                onBack = { navController.popBackStack() },
                onSelectSpace = { spaceId ->
                    navController.navigate(Screen.AddMapping(spaceId, firstBinding = true).route)
                }
            )
        }

        composable(Screen.SpaceEdit.ROUTE) { entry ->
            val spaceId = requireNotNull(entry.arguments?.getString("spaceId"))
            SpaceEditScreen(
                spaceId = spaceId,
                onBack = { navController.popBackStack() },
                onSaved = { _, _ -> navController.popBackStack() }
            )
        }

        composable(Screen.SpaceDetail.ROUTE) { entry ->
            val spaceId = requireNotNull(entry.arguments?.getString("spaceId"))
            SpaceDetailScreen(
                spaceId = spaceId,
                onBack = { navController.popBackStack() },
                onEditSpace = { navController.navigate(Screen.SpaceEdit(it).route) },
                onAddMapping = { navController.navigate(Screen.AddMapping(it).route) },
                onOpenMapping = { navController.navigate(Screen.MappingFiles(it).route) },
                onOpenTrash = { navController.navigate(Screen.SpaceTrash(it).route) },
                onOpenActivityLog = { navController.navigate(Screen.ActivityLog(it).route) }
            )
        }

        composable(
            Screen.AddMapping.ROUTE,
            arguments = listOf(navArgument("first") {
                type = NavType.BoolType
                defaultValue = false
            })
        ) { entry ->
            val spaceId = requireNotNull(entry.arguments?.getString("spaceId"))
            val firstBinding = entry.arguments?.getBoolean("first") ?: false
            AddMappingScreen(
                spaceId = spaceId,
                onBack = { navController.popBackStack() },
                onFinished = {
                    if (firstBinding) {
                        navController.navigate(Screen.SpaceDetail(spaceId).route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(Screen.MappingFiles.ROUTE) { entry ->
            MappingFilesScreen(
                mappingId = requireNotNull(entry.arguments?.getString("mappingId")),
                onBack = { navController.popBackStack() },
                onOpenVersions = { navController.navigate(Screen.FileVersions(it).route) }
            )
        }

        composable(Screen.ActivityLog.ROUTE) { entry ->
            ActivityScreen(
                spaceId = entry.arguments?.getString("spaceId"),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SpaceTrash.ROUTE) { entry ->
            TrashScreen(
                spaceId = requireNotNull(entry.arguments?.getString("spaceId")),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.FileVersions.ROUTE) { entry ->
            FileVersionsScreen(
                fileId = requireNotNull(entry.arguments?.getString("fileId")),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
