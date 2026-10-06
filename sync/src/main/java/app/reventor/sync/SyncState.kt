package app.reventor.sync

/** Simple observable status for the setup UI; written by [SyncService]. */
object SyncState {
    @Volatile var serviceRunning: Boolean = false
    @Volatile var statusLine: String = "Idle"
    @Volatile var connectedPeer: String? = null
}
