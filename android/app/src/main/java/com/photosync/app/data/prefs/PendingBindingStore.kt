package com.photosync.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.pendingBindingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pending_bindings"
)

/** 已选本地目录的暂存信息：设备尚未成为成员时先保存，审批通过后再真正建立映射。 */
data class PendingBinding(
    val spaceId: String,
    val treeUri: String,
    val localPathLabel: String,
    val onlyMedia: Boolean
)

/** 按空间暂存「绑定目录 + 加入申请」中的本地目录选择。 */
class PendingBindingStore(private val context: Context) {

    fun observe(spaceId: String): Flow<PendingBinding?> = data().map { prefs -> read(prefs, spaceId) }

    suspend fun get(spaceId: String): PendingBinding? = observe(spaceId).first()

    suspend fun save(binding: PendingBinding) {
        context.pendingBindingDataStore.edit { prefs ->
            prefs[treeKey(binding.spaceId)] = binding.treeUri
            prefs[labelKey(binding.spaceId)] = binding.localPathLabel
            prefs[onlyMediaKey(binding.spaceId)] = binding.onlyMedia
        }
    }

    suspend fun clear(spaceId: String) {
        context.pendingBindingDataStore.edit { prefs ->
            prefs.remove(treeKey(spaceId))
            prefs.remove(labelKey(spaceId))
            prefs.remove(onlyMediaKey(spaceId))
        }
    }

    private fun data(): Flow<Preferences> = context.pendingBindingDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private fun read(prefs: Preferences, spaceId: String): PendingBinding? {
        val treeUri = prefs[treeKey(spaceId)] ?: return null
        return PendingBinding(
            spaceId = spaceId,
            treeUri = treeUri,
            localPathLabel = prefs[labelKey(spaceId)] ?: "已选目录",
            onlyMedia = prefs[onlyMediaKey(spaceId)] ?: false
        )
    }

    private fun treeKey(spaceId: String) = stringPreferencesKey("pending_tree_$spaceId")

    private fun labelKey(spaceId: String) = stringPreferencesKey("pending_label_$spaceId")

    private fun onlyMediaKey(spaceId: String) = booleanPreferencesKey("pending_only_media_$spaceId")
}
