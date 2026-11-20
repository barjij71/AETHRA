package org.aethra.assistant.aethra.adapters

/**
 * Storage for the user-selected InstaBox machine ID.
 *
 * The machine ID is not a secret (it identifies a machine the user has chosen),
 * so it may be stored in ordinary SharedPreferences. The API key, by contrast,
 * must never be stored here.
 */
interface SelectedMachineStore {
    /** Returns the currently selected machine ID, or null if none is selected. */
    fun getSelectedMachineId(): String?

    /** Persists the selected machine ID. */
    fun setSelectedMachineId(machineId: String)

    /** Clears the selected machine ID. */
    fun clearSelectedMachineId()
}
