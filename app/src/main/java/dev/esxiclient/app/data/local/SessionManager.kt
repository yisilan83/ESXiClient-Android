package dev.esxiclient.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 使用 Kotlin 委托属性在 Context 上创建一个单例 DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "esxi_settings")

/**
 * 负责在本地安全存储和读取会话信息的管理器
 */
class SessionManager(private val context: Context) {

    companion object {
        val HOST_KEY = stringPreferencesKey("host")
        val SESSION_ID_KEY = stringPreferencesKey("session_id")
        val USERNAME_KEY = stringPreferencesKey("username")
    }

    // 保存登录成功后的会话信息
    suspend fun saveSession(host: String, sessionId: String, username: String) {
        context.dataStore.edit { prefs ->
            prefs[HOST_KEY] = host
            prefs[SESSION_ID_KEY] = sessionId
            prefs[USERNAME_KEY] = username
        }
    }

    // 清除会话信息（登出时使用）
    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(SESSION_ID_KEY)
        }
    }

    // 将这些值以 Flow (响应式数据流) 的形式提供出去
    val hostFlow: Flow<String?> = context.dataStore.data.map { it[HOST_KEY] }
    val sessionIdFlow: Flow<String?> = context.dataStore.data.map { it[SESSION_ID_KEY] }
    val usernameFlow: Flow<String?> = context.dataStore.data.map { it[USERNAME_KEY] }
}