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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson

class SharedPrefProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor {
        Utils.debugLog("Got URI as - " + uri.toString())
        val packageName = uri.toString()
            .replaceFirst("content://chanhnh.alltrans.SharedPrefProvider/".toRegex(), "")
        val cols = arrayOf<String?>("sharedPreferences")
        val cursor = MatrixCursor(cols)

        val globalPref =
            this.getContext()!!.getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)
        var builder = cursor.newRow()
        val globalPrefGson = Gson().toJson(globalPref.getAll())
        builder.add(globalPrefGson)
        Utils.debugLog("Got globalpref as - " + globalPrefGson + " for package " + packageName)
        Utils.debugLog(
            "Got boolean as - " + globalPref.getBoolean(
                packageName,
                false
            ) + " for package " + packageName
        )
        if (globalPref.getBoolean(packageName, false)) {
            val localPrefGson = Gson().toJson(
                this.getContext()!!.getSharedPreferences(packageName, Context.MODE_PRIVATE).getAll()
            )
            Utils.debugLog("Got localpref as - " + localPrefGson + " for package " + packageName)
            builder = cursor.newRow()
            builder.add(localPrefGson)
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        return 0
    }
}
