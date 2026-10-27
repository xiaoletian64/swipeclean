package com.zhangyue.swipeclean.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "swipeclean")

/** 轻量持久化：黑名单相似组、累计清理数、声音偏好 */
class Prefs(private val context: Context) {
    private val KEY_BLACKLIST = stringSetPreferencesKey("similar_blacklist")
    private val KEY_CLEARED = stringSetPreferencesKey("cleared_ids")
    private val KEY_UNMUTED = booleanPreferencesKey("video_unmuted")

    val blacklist: Flow<Set<String>> = context.dataStore.data.map { it[KEY_BLACKLIST] ?: emptySet() }
    val clearedIds: Flow<Set<String>> = context.dataStore.data.map { it[KEY_CLEARED] ?: emptySet() }
    val videoUnmuted: Flow<Boolean> = context.dataStore.data.map { it[KEY_UNMUTED] ?: false }

    suspend fun addBlacklist(key: String) {
        context.dataStore.edit { it[KEY_BLACKLIST] = (it[KEY_BLACKLIST] ?: emptySet()) + key }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it[KEY_BLACKLIST] = emptySet() }
    }

    suspend fun addCleared(ids: List<Long>) {
        context.dataStore.edit {
            it[KEY_CLEARED] = (it[KEY_CLEARED] ?: emptySet()) + ids.map { id -> id.toString() }
        }
    }

    suspend fun clearedSnapshot(): Set<String> = clearedIds.first()

    /** 视频默认静音；用户开声后记忆 */
    suspend fun setVideoUnmuted(v: Boolean) {
        context.dataStore.edit { it[KEY_UNMUTED] = v }
    }

    suspend fun unmutedSnapshot(): Boolean = videoUnmuted.first()
}
