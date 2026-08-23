package com.github.libretube.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadSponsorBlockSegment
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.BackupFile
import com.github.libretube.obj.DownloadBackup
import com.github.libretube.obj.DownloadChapterBackup
import com.github.libretube.obj.DownloadItemBackup
import com.github.libretube.obj.DownloadSponsorBlockSegmentBackup
import com.github.libretube.obj.DownloadsBackup
import com.github.libretube.obj.FullBackupMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists

/**
 * Full backup and restore including downloaded files (video, audio, thumbnail, subtitle).
 * Creates a ZIP (store mode, no compression) containing backup.json, downloads.json,
 * metadata.json, and all downloaded media files.
 */
object FullBackupHelper {
    private const val METADATA_ENTRY = "metadata.json"
    private const val BACKUP_ENTRY = "backup.json"
    private const val DOWNLOADS_ENTRY = "downloads.json"
    private const val VIDEO_DIR = "video"
    private const val AUDIO_DIR = "audio"
    private const val THUMBNAIL_DIR = "thumbnail"
    private const val SUBTITLE_DIR = "subtitle"

    /**
     * Get the current database version.
     */
    private val dbVersion: Int
        get() = DatabaseHolder.Database.openHelper.readableDatabase.version

    /**
     * Create a full backup ZIP containing all app data and downloaded files.
     * Uses ZIP store mode (no compression) for fast creation and large media files.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createFullBackup(
        context: Context,
        uri: Uri,
        backupFile: BackupFile
    ) = withContext(Dispatchers.IO) {
        try {
            val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val timestamp = System.currentTimeMillis().toString()

            context.contentResolver.openOutputStream(uri)?.use { rawStream ->
                BufferedOutputStream(rawStream).use { buffered ->
                    ZipOutputStream(buffered).use { zip ->
                        // 1. Write metadata.json
                        val metadata = FullBackupMetadata(
                            dbVersion = dbVersion,
                            timestamp = timestamp
                        )
                        writeJsonEntry(zip, METADATA_ENTRY, JsonHelper.json.encodeToString(metadata))

                        // 2. Write backup.json (same format as advanced backup)
                        writeJsonEntry(zip, BACKUP_ENTRY, JsonHelper.json.encodeToString(backupFile))

                        // 3. Collect and write downloads.json
                        val downloads = DatabaseHolder.Database.downloadDao().getAll()
                        val downloadsBackup = DownloadsBackup(
                            downloads = downloads.map { dwi ->
                                DownloadBackup(
                                    videoId = dwi.download.videoId,
                                    title = dwi.download.title,
                                    description = dwi.download.description,
                                    uploader = dwi.download.uploader,
                                    duration = dwi.download.duration,
                                    uploadDate = dwi.download.uploadDate?.toString(),
                                    thumbnailPath = dwi.download.thumbnailPath?.let { java.io.File(it.toString()).name },
                                    uploaderUrl = dwi.download.uploaderUrl,
                                    views = dwi.download.views,
                                    likes = dwi.download.likes,
                                    dislikes = dwi.download.dislikes,
                                    items = dwi.downloadItems.map { item ->
                                        DownloadItemBackup(
                                            type = item.type,
                                            videoId = item.videoId,
                                            fileName = item.fileName,
                                            relativePath = getRelativePath(item),
                                            url = null,
                                            format = item.format,
                                            quality = item.quality,
                                            language = item.language,
                                            downloadSize = item.downloadSize
                                        )
                                    },
                                    chapters = dwi.downloadChapters.map { ch ->
                                        DownloadChapterBackup(
                                            videoId = ch.videoId,
                                            name = ch.name,
                                            start = ch.start,
                                            thumbnailUrl = ch.thumbnailUrl
                                        )
                                    },
                                    sponsorBlockSegments = dwi.downloadSponsorBlockSegments.map { seg ->
                                        DownloadSponsorBlockSegmentBackup(
                                            uuid = seg.uuid,
                                            videoId = seg.videoId,
                                            actionType = seg.actionType,
                                            category = seg.category,
                                            description = seg.description,
                                            locked = seg.locked,
                                            startTime = seg.startTime,
                                            endTime = seg.endTime,
                                            videoDuration = seg.videoDuration,
                                            votes = seg.votes
                                        )
                                    }
                                )
                            }
                        )
                        writeJsonEntry(zip, DOWNLOADS_ENTRY, JsonHelper.json.encodeToString(downloadsBackup))

                        // 4. Add media files from each directory
                        addDirectoryToZip(zip, filesDir.toPath() / VIDEO_DIR, VIDEO_DIR)
                        addDirectoryToZip(zip, filesDir.toPath() / AUDIO_DIR, AUDIO_DIR)
                        addDirectoryToZip(zip, filesDir.toPath() / THUMBNAIL_DIR, THUMBNAIL_DIR)
                        addDirectoryToZip(zip, filesDir.toPath() / SUBTITLE_DIR, SUBTITLE_DIR)
                    }
                }
            }
            context.toastFromMainDispatcher(R.string.backup_creation_success)
        } catch (e: Exception) {
            Log.e(TAG(), "Error while creating full backup: $e")
            context.toastFromMainDispatcher(R.string.backup_creation_failed)
        }
    }

    /**
     * Restore a full backup ZIP.
     * Checks DB version compatibility before touching anything.
     * Returns true if restore succeeded, false if incompatible.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun restoreFullBackup(
        context: Context,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.getExternalFilesDir(null) ?: context.filesDir

        try {
            // 1. Read metadata first to check DB version compatibility
            val metadata = context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == METADATA_ENTRY) {
                            val text = zip.bufferedReader().readText()
                            return@use JsonHelper.json.decodeFromString<FullBackupMetadata>(text)
                        }
                        entry = zip.nextEntry
                    }
                    null
                }
            }

            if (metadata == null) {
                context.toastFromMainDispatcher(R.string.full_backup_invalid)
                return@withContext false
            }

            // 2. Check DB version compatibility
            val currentDbVersion = dbVersion
            if (metadata.dbVersion > currentDbVersion) {
                context.toastFromMainDispatcher(R.string.full_backup_newer_db)
                return@withContext false
            }

            // 3. Parse the ZIP - stream media files directly to disk (no in-memory buffering)
            var backupFile: BackupFile? = null
            var downloadsBackup: DownloadsBackup? = null

            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == BACKUP_ENTRY -> {
                                backupFile = JsonHelper.json.decodeFromStream<BackupFile>(zip)
                            }
                            entry.name == DOWNLOADS_ENTRY -> {
                                downloadsBackup = JsonHelper.json.decodeFromStream<DownloadsBackup>(zip)
                            }
                            entry.name.startsWith("$VIDEO_DIR/") ||
                            entry.name.startsWith("$AUDIO_DIR/") ||
                            entry.name.startsWith("$THUMBNAIL_DIR/") ||
                            entry.name.startsWith("$SUBTITLE_DIR/") -> {
                                // Stream directly to file - no in-memory buffering
                                val parts = entry.name.split("/", limit = 2)
                                if (parts.size == 2) {
                                    val dirName = parts[0]
                                    val fileName = parts[1]
                                    val targetDir = filesDir.toPath() / dirName
                                    targetDir.createDirectories()
                                    val targetFile = targetDir / fileName
                                    targetFile.toFile().outputStream().use { out ->
                                        zip.copyTo(out)
                                    }
                                }
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            // 4. Restore standard backup data (watch history, subscriptions, playlists, prefs)
            backupFile?.let { BackupHelper.restoreAdvancedBackupData(context, it) }

            // 5. Restore downloads (DB entries)
            downloadsBackup?.let { restoreDownloads(context, it) }

            context.toastFromMainDispatcher(R.string.restore_success)
            true
        } catch (e: Exception) {
            Log.e(TAG(), "Error while restoring full backup: $e")
            context.toastFromMainDispatcher(R.string.restore_failed)
            false
        }
    }

    /**
     * Restore download entries into the database from the backup.
     */
    private suspend fun restoreDownloads(context: Context, backup: DownloadsBackup) {
        val db = DatabaseHolder.Database
        val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
        backup.downloads.forEach { dl ->
            val download = Download(
                videoId = dl.videoId,
                title = dl.title,
                description = dl.description,
                uploader = dl.uploader,
                duration = dl.duration,
                uploadDate = dl.uploadDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                thumbnailPath = dl.thumbnailPath?.let { thumbPath ->
                    val fileName = java.io.File(thumbPath).name
                    filesDir.toPath().resolve("thumbnail").resolve(fileName)
                },
                uploaderUrl = dl.uploaderUrl,
                views = dl.views,
                likes = dl.likes,
                dislikes = dl.dislikes
            )
            db.downloadDao().insertDownload(download)

            dl.chapters.forEach { ch ->
                db.downloadDao().insertDownloadChapter(
                    DownloadChapter(
                        videoId = ch.videoId,
                        name = ch.name,
                        start = ch.start,
                        thumbnailUrl = ch.thumbnailUrl
                    )
                )
            }

            dl.sponsorBlockSegments.forEach { seg ->
                db.downloadDao().insertSponsorBlockSegments(
                    listOf(
                        DownloadSponsorBlockSegment(
                            uuid = seg.uuid,
                            videoId = seg.videoId,
                            actionType = seg.actionType,
                            category = seg.category,
                            description = seg.description,
                            locked = seg.locked,
                            startTime = seg.startTime,
                            endTime = seg.endTime,
                            videoDuration = seg.videoDuration,
                            votes = seg.votes
                        )
                    )
                )
            }

            dl.items.forEach { item ->
                val downloadItem = DownloadItem(
                    type = item.type,
                    videoId = item.videoId,
                    fileName = item.fileName,
                    path = filesDir.toPath().resolve(item.relativePath),
                    format = item.format,
                    quality = item.quality,
                    language = item.language,
                    downloadSize = item.downloadSize
                )
                db.downloadDao().insertDownloadItem(downloadItem)
            }
        }
    }

    /**
     * Get the relative path of a download item for storage in the ZIP.
     */
    private fun getRelativePath(item: DownloadItem): String {
        val dir = when (item.type) {
            FileType.VIDEO -> VIDEO_DIR
            FileType.AUDIO -> AUDIO_DIR
            FileType.SUBTITLE -> SUBTITLE_DIR
        }
        return "$dir/${item.fileName}"
    }

    /**
     * Write a JSON string as a ZIP entry (store mode, no compression).
     */
    private fun writeJsonEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        val bytes = content.toByteArray(Charsets.UTF_8)
        entry.size = bytes.size.toLong()
        entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Add all files from a directory to the ZIP (store mode, no compression).
     */
    private fun addDirectoryToZip(zip: ZipOutputStream, dirPath: java.nio.file.Path, zipDirName: String) {
        if (dirPath.notExists()) return
        dirPath.listDirectoryEntries().forEach { filePath ->
            val fileName = filePath.fileName.toString()
            val entry = ZipEntry("$zipDirName/$fileName")
            entry.method = ZipEntry.STORED
            val size = filePath.fileSize()
            entry.size = size

            // Calculate CRC32
            val crc = java.util.zip.CRC32()
            filePath.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    crc.update(buffer, 0, read)
                }
            }
            entry.crc = crc.value

            zip.putNextEntry(entry)
            filePath.inputStream().use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }
}
