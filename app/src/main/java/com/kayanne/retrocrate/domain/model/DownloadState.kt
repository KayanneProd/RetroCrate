package com.kayanne.retrocrate.domain.model

sealed interface DownloadState {
    data object NotStarted : DownloadState
    data class Queued(val position: Int) : DownloadState
    data class InProgress(val bytesDone: Long, val bytesTotal: Long?) : DownloadState
    data class Failed(val reason: String) : DownloadState
    data class Completed(val filePath: String) : DownloadState
}
