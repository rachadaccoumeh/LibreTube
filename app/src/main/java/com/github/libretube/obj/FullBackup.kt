package com.github.libretube.obj

import com.github.libretube.enums.FileType
import kotlinx.serialization.Serializable

/**
 * Serializable wrapper for [com.github.libretube.db.obj.Download] and its related entities.
 * Used for full ZIP backup/restore since Room entities are not @Serializable.
 */
@Serializable
data class FullBackupMetadata(
    val dbVersion: Int,
    val appVersion: String? = null,
    val timestamp: String
)

@Serializable
data class DownloadBackup(
    val videoId: String,
    val title: String = "",
    val description: String = "",
    val uploader: String = "",
    val duration: Long? = null,
    val uploadDate: String? = null,
    val thumbnailPath: String? = null,
    val uploaderUrl: String? = null,
    val views: Long = 0,
    val likes: Long = 0,
    val dislikes: Long = -1,
    val items: List<DownloadItemBackup> = emptyList(),
    val chapters: List<DownloadChapterBackup> = emptyList(),
    val sponsorBlockSegments: List<DownloadSponsorBlockSegmentBackup> = emptyList()
)

@Serializable
data class DownloadItemBackup(
    val type: FileType,
    val videoId: String,
    val fileName: String,
    val relativePath: String,
    val url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val language: String? = null,
    val downloadSize: Long = -1L
)

@Serializable
data class DownloadChapterBackup(
    val videoId: String,
    val name: String,
    val start: Long,
    val thumbnailUrl: String
)

@Serializable
data class DownloadSponsorBlockSegmentBackup(
    val uuid: String,
    val videoId: String,
    val actionType: String,
    val category: String,
    val description: String? = null,
    val locked: Int,
    val startTime: Float,
    val endTime: Float,
    val videoDuration: Float,
    val votes: Int
)

@Serializable
data class DownloadsBackup(
    val downloads: List<DownloadBackup> = emptyList()
)
