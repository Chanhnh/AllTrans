/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package chanhnh.alltrans

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PreferenceManager {
    private const val TAG = "AllTrans:PrefManager"
    private const val CACHE_VALIDITY_MS = 5000L // Cache válido por 5 segundos

    private val preferenceCache = ConcurrentHashMap<String, CachedPreferences>()

    data class CachedPreferences(
        val enabled: Boolean,
        val localEnabled: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Verifica se o AllTrans está habilitado para o pacote especificado
     */
    fun isEnabledForPackage(context: Context?, packageName: String?): Boolean {
        if (context == null || packageName.isNullOrEmpty()) {
            return false
        }

        val cached = preferenceCache[packageName]
        val now = System.currentTimeMillis()

        // Se tem cache válido, usa ele
        if (cached != null && (now - cached.timestamp) < CACHE_VALIDITY_MS) {
            return cached.enabled && cached.localEnabled
        }

        // Senão, recarrega as preferências
        return reloadPreferencesForPackage(context, packageName)
    }

    /**
     * Força o recarregamento das preferências para um pacote
     */
    private fun reloadPreferencesForPackage(context: Context, packageName: String): Boolean {
        Utils.debugLog("$TAG: Reloading preferences for $packageName")

        try {
            val prefsBundle = getPreferencesViaProviders(context, packageName)
            if (prefsBundle == null) {
                Utils.debugLog("$TAG: No preferences found for $packageName")
                preferenceCache[packageName] = CachedPreferences(false, false)
                return false
            }

            val globalPref = prefsBundle.getString("globalPref")
            val localPref = prefsBundle.getString("localPref")

            if (globalPref.isNullOrEmpty()) {
                Utils.debugLog("$TAG: Empty global preferences for $packageName")
                preferenceCache[packageName] = CachedPreferences(false, false)
                return false
            }

            // Aplicar preferências temporariamente para verificar status
            val oldEnabled = PreferenceList.Enabled
            val oldLocalEnabled = PreferenceList.LocalEnabled

            try {
                PreferenceList.getPref(globalPref, localPref ?: "", packageName)
                val enabled = PreferenceList.Enabled
                val localEnabled = PreferenceList.LocalEnabled

                // Cachear o resultado
                preferenceCache[packageName] = CachedPreferences(enabled, localEnabled)

                Utils.debugLog("$TAG: Preferences for $packageName - Enabled: $enabled, LocalEnabled: $localEnabled")
                return enabled && localEnabled

            } finally {
                // Restaurar valores originais
                PreferenceList.Enabled = oldEnabled
                PreferenceList.LocalEnabled = oldLocalEnabled
            }

        } catch (e: Exception) {
            Utils.debugLog("$TAG: Error reloading preferences for $packageName: ${e.message}")
            preferenceCache[packageName] = CachedPreferences(false, false)
            return false
        }
    }

    /**
     * Limpa o cache de preferências para forçar recarregamento
     */
    fun clearCache(packageName: String? = null) {
        if (packageName != null) {
            preferenceCache.remove(packageName)
            Utils.debugLog("$TAG: Cleared cache for $packageName")
        } else {
            preferenceCache.clear()
            Utils.debugLog("$TAG: Cleared all preference cache")
        }
    }

    private fun getPreferencesViaProviders(context: Context?, packageName: String?): Bundle? {
        if (context == null || packageName == null) return null
        val resultBundle = Bundle()
        var globalPref: String? = null
        var localPref: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()

        try {
            // Tentar primeiro via proxy
            if (Alltrans.isProxyEnabled) {
                try {
                    val proxyUri = "content://settings/system/alltransProxyProviderURI/chanhnh.alltrans.SharedPrefProvider/$packageName".toUri()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val queryArgSqlSelectionBehavior = "android:queryArgSelectionBehavior"
                        val selectionBehaviorStrict = 0
                        val extraHonoredArgs = "android:honorsExtraArgs"
                        val queryArgAsyncComputation = "android:asyncQuery"

                        val queryArgs = Bundle()
                        queryArgs.putInt(queryArgSqlSelectionBehavior, selectionBehaviorStrict)
                        queryArgs.putBoolean(queryArgAsyncComputation, true)
                        queryArgs.putInt(extraHonoredArgs, 1)

                        cursor = context.contentResolver.query(proxyUri, null, queryArgs, null)
                    } else {
                        cursor = context.contentResolver.query(proxyUri, null, null, null, null)
                    }

                    if (cursor != null && cursor.moveToFirst()) {
                        val dataColumnIndex = 0
                        if (cursor.columnCount > dataColumnIndex) {
                            globalPref = cursor.getString(dataColumnIndex)
                            if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: Proxy query exception: ${e.message}")
                } finally {
                    cursor?.close()
                    cursor = null
                }
            }

            // Se o proxy falhar, tentar conexão direta
            if (globalPref == null) {
                try {
                    val directUri = "content://chanhnh.alltrans.SharedPrefProvider/$packageName".toUri()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val queryArgSqlSelectionBehavior = "android:queryArgSelectionBehavior"
                        val selectionBehaviorStrict = 0
                        val extraHonoredArgs = "android:honorsExtraArgs"
                        val queryArgAsyncComputation = "android:asyncQuery"

                        val queryArgs = Bundle()
                        queryArgs.putInt(queryArgSqlSelectionBehavior, selectionBehaviorStrict)
                        queryArgs.putBoolean(queryArgAsyncComputation, true)
                        queryArgs.putInt(extraHonoredArgs, 1)

                        cursor = context.contentResolver.query(directUri, null, queryArgs, null)
                    } else {
                        cursor = context.contentResolver.query(directUri, null, null, null, null)
                    }

                    if (cursor != null && cursor.moveToFirst()) {
                        val dataColumnIndex = 0
                        if (cursor.columnCount > dataColumnIndex) {
                            globalPref = cursor.getString(dataColumnIndex)
                            if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: Direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }

            if (globalPref != null) {
                resultBundle.putString("globalPref", globalPref)
                resultBundle.putString("localPref", localPref ?: "")
                return resultBundle
            } else {
                return null
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}