package org.aethra.assistant.aethra.adapters

import android.content.Context
import android.content.SharedPreferences

/**
 * [SelectedMachineStore] backed by Android SharedPreferences.
 *
 * The machine ID is not sensitive, so ordinary SharedPreferences is acceptable.
 * The API key must never be stored here.
 */
class AndroidSelectedMachineStore(private val context: Context) : SelectedMachineStore {

    private companion object {
        private const val PREFS_NAME = "aethra.instabox.machine"
        private const val KEY_SELECTED_MACHINE_ID = "selected_machine_id"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getSelectedMachineId(): String? = prefs.getString(KEY_SELECTED_MACHINE_ID, null)

    override fun setSelectedMachineId(machineId: String) {
        prefs.edit().putString(KEY_SELECTED_MACHINE_ID, machineId).apply()
    }

    override fun clearSelectedMachineId() {
        prefs.edit().remove(KEY_SELECTED_MACHINE_ID).apply()
    }
}
