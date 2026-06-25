package com.kayanne.retrocrate.domain.model

sealed interface DownloadState {
    data object NotStarted : DownloadState
    data class Queued(val position: Int) : DownloadState
    // The debrid service is fetching a not-yet-cached torrent server-side before we can download it.
    // `progress` is 0..1 when known. The actual device download (InProgress) follows once it's ready.
    data class Preparing(val message: String, val progress: Float? = null) : DownloadState
    data class InProgress(val bytesDone: Long, val bytesTotal: Long?) : DownloadState
    data class Failed(val reason: String) : DownloadState
    data class Completed(val filePath: String) : DownloadState
}
