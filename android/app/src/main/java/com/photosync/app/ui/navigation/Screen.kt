package com.photosync.app.ui.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")
    data object ServerConfig : Screen("server_config")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object SpaceCreate : Screen("space_edit")

    data object JoinSpace : Screen("join_space")

    data class SpaceEdit(val spaceId: String) : Screen("space_edit/$spaceId") {
        companion object { const val ROUTE = "space_edit/{spaceId}" }
    }

    data class SpaceDetail(val spaceId: String) : Screen("space_detail/$spaceId") {
        companion object { const val ROUTE = "space_detail/{spaceId}" }
    }

    /** [firstBinding] 为 true 表示建空间/加入空间后的首次目录绑定，完成后进入空间详情。 */
    data class AddMapping(
        val spaceId: String,
        val firstBinding: Boolean = false
    ) : Screen("add_mapping/$spaceId?first=$firstBinding") {
        companion object { const val ROUTE = "add_mapping/{spaceId}?first={first}" }
    }

    data class MappingFiles(val mappingId: String) : Screen("mapping_files/$mappingId") {
        companion object { const val ROUTE = "mapping_files/{mappingId}" }
    }

    data object ActivityLog : Screen("activity")

    data class SpaceTrash(val spaceId: String) : Screen("trash/$spaceId") {
        companion object { const val ROUTE = "trash/{spaceId}" }
    }

    data class FileVersions(val fileId: String) : Screen("versions/$fileId") {
        companion object { const val ROUTE = "versions/{fileId}" }
    }
}
