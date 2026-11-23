package com.photosync.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SyncDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_folder_mappings` (" +
                    "`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                    "`localPathLabel` TEXT NOT NULL, `treeUri` TEXT NOT NULL, `remotePrefix` TEXT NOT NULL, " +
                    "`includeSubdirs` INTEGER NOT NULL, `onlyMedia` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`lastScanComplete` INTEGER NOT NULL, `lastScannedAt` TEXT, `createdAt` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_local_folder_mappings_spaceId_deviceId` " +
                    "ON `local_folder_mappings` (`spaceId`, `deviceId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_file_index` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mappingId` TEXT NOT NULL, " +
                    "`relativePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                    "`modifiedAt` INTEGER NOT NULL, `sha256` TEXT, `docUri` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, `lastScannedAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_file_index_mappingId_relativePath` " +
                    "ON `local_file_index` (`mappingId`, `relativePath`)"
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_file_index` ADD COLUMN `remoteFileId` TEXT")
            db.execSQL("ALTER TABLE `local_file_index` ADD COLUMN `remoteVersion` INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_sync_tasks` (" +
                    "`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `mappingId` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `state` TEXT NOT NULL, `localRelativePath` TEXT NOT NULL, " +
                    "`remoteRelativePath` TEXT NOT NULL, `fileId` TEXT, `objectId` TEXT, `uploadId` TEXT, " +
                    "`size` INTEGER NOT NULL, `sha256` TEXT, `operationId` TEXT NOT NULL, `eventRevision` TEXT, " +
                    "`uploadedParts` INTEGER NOT NULL, `bytesTransferred` INTEGER NOT NULL, " +
                    "`nextPartNumber` INTEGER NOT NULL, `attempt` INTEGER NOT NULL, `error` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_local_sync_tasks_spaceId_state` " +
                    "ON `local_sync_tasks` (`spaceId`, `state`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_local_sync_tasks_spaceId_mappingId_remoteRelativePath_type` " +
                    "ON `local_sync_tasks` (`spaceId`, `mappingId`, `remoteRelativePath`, `type`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_sync_tasks_spaceId_eventRevision` " +
                    "ON `local_sync_tasks` (`spaceId`, `eventRevision`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_sync_cursors` (" +
                    "`spaceId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `lastAppliedRevision` TEXT NOT NULL, " +
                    "`serverRevision` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`spaceId`, `deviceId`))"
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_sync_tasks` ADD COLUMN `baseVersion` INTEGER")
            db.execSQL("ALTER TABLE `local_sync_tasks` ADD COLUMN `oldLocalRelativePath` TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_devices` ADD COLUMN `isPrimary` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `local_devices` ADD COLUMN `isRejoinRequested` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /** 一个空间在一台设备上只保留一条目录映射，远端树直接由该目录结构决定。 */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM `local_folder_mappings` WHERE `rowid` NOT IN (" +
                    "SELECT m.`rowid` FROM `local_folder_mappings` m WHERE NOT EXISTS (" +
                    "SELECT 1 FROM `local_folder_mappings` n " +
                    "WHERE n.`spaceId` = m.`spaceId` AND n.`deviceId` = m.`deviceId` AND (" +
                    "COALESCE(n.`createdAt`, '') > COALESCE(m.`createdAt`, '') OR (" +
                    "COALESCE(n.`createdAt`, '') = COALESCE(m.`createdAt`, '') AND n.`rowid` > m.`rowid`))))"
            )
            db.execSQL(
                "DELETE FROM `local_file_index` WHERE `mappingId` NOT IN " +
                    "(SELECT `id` FROM `local_folder_mappings`)"
            )
            db.execSQL(
                "DELETE FROM `local_sync_tasks` WHERE `mappingId` NOT IN " +
                    "(SELECT `id` FROM `local_folder_mappings`)"
            )
            db.execSQL("ALTER TABLE `local_folder_mappings` RENAME TO `local_folder_mappings_old`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_folder_mappings` (" +
                    "`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                    "`localPathLabel` TEXT NOT NULL, `treeUri` TEXT NOT NULL, " +
                    "`onlyMedia` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`lastScanComplete` INTEGER NOT NULL, `lastScannedAt` TEXT, `createdAt` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO `local_folder_mappings` (`id`, `spaceId`, `deviceId`, " +
                    "`localPathLabel`, `treeUri`, `onlyMedia`, `status`, `lastScanComplete`, " +
                    "`lastScannedAt`, `createdAt`) SELECT `id`, `spaceId`, `deviceId`, " +
                    "`localPathLabel`, `treeUri`, `onlyMedia`, `status`, `lastScanComplete`, " +
                    "`lastScannedAt`, `createdAt` FROM `local_folder_mappings_old`"
            )
            db.execSQL("DROP TABLE `local_folder_mappings_old`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_folder_mappings_spaceId_deviceId` " +
                    "ON `local_folder_mappings` (`spaceId`, `deviceId`)"
            )
        }
    }
}
