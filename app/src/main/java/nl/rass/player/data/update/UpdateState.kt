package nl.rass.player.data.update

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val versionName: String, val notesFa: String, val sizeBytes: Long?) : UpdateState()
    /** [progress] is 0f..1f, or -1f when the total size is unknown. */
    data class Downloading(val progress: Float) : UpdateState()
    data object Verifying : UpdateState()
    data object ReadyToInstall : UpdateState()
    data object Installing : UpdateState()
    data class Failed(val reasonFa: String) : UpdateState()
}
