package com.rafeeq.assistant.skills

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verified outcome of an organization run.
 *
 * [found] is how many images the query produced and [moved] how many were actually relocated;
 * the difference is reported to the user instead of being folded into a generic success, since
 * a successful query is not the same thing as successful organization.
 */
data class OrganizeSummary(
    val found: Int,
    val moved: Int
)

class OrganizeImagesSkill(context: Context) {
    private val appContext = context.applicationContext

    var progressCallback: ((ProcessingState, Map<String, String>) -> Unit)? = null

    /**
     * Live counts from the most recent [execute] run. They let a cancelled run report its real
     * partial outcome instead of a guess.
     */
    @Volatile
    var lastFoundCount: Int = 0
        private set

    @Volatile
    var lastMovedCount: Int = 0
        private set

    /**
     * Counts the images [execute] would touch, without modifying anything. Lets the caller ask
     * for consent before a bulk operation starts.
     */
    suspend fun countImages(sortBy: String = SORT_BY_DATE): Int = withContext(Dispatchers.IO) {
        if (!hasMediaReadPermission()) return@withContext 0
        try {
            queryImages().size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query images from MediaStore", e)
            0
        }
    }

    suspend fun execute(sortBy: String = SORT_BY_DATE): OrganizeSummary = withContext(Dispatchers.IO) {
        lastFoundCount = 0
        lastMovedCount = 0
        progressCallback?.invoke(ProcessingState.SEARCHING_FILES, emptyMap())
        if (!hasMediaReadPermission()) {
            Log.w(TAG, "Media read permission is not granted; skipping image organization")
            return@withContext OrganizeSummary(found = 0, moved = 0)
        }

        val images = try {
            queryImages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query images from MediaStore", e)
            progressCallback?.invoke(ProcessingState.COMPLETED, emptyMap())
            return@withContext OrganizeSummary(found = 0, moved = 0)
        }

        lastFoundCount = images.size
        if (images.isNotEmpty()) {
            progressCallback?.invoke(
                ProcessingState.MOVING_FILES,
                mapOf("count" to images.size.toString(), "moved" to "0")
            )
        }

        var movedCount = 0
        var processedCount = 0
        for (image in images) {
            try {
                if (moveImage(image, targetFolderFor(image, sortBy))) {
                    movedCount++
                    lastMovedCount = movedCount
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A single unreadable or locked file must not abort the rest of the batch.
                Log.e(TAG, "Failed to move image '${image.displayName}'", e)
            }
            processedCount++
            // Publish coarse progress so a background notification can follow along; the
            // notification manager throttles these further before showing anything.
            if (processedCount % PROGRESS_EVERY == 0) {
                progressCallback?.invoke(
                    ProcessingState.MOVING_FILES,
                    mapOf(
                        "count" to images.size.toString(),
                        "moved" to movedCount.toString()
                    )
                )
            }
        }

        Log.i(TAG, "Image organization finished: moved $movedCount of ${images.size} images")
        progressCallback?.invoke(ProcessingState.COMPLETED, emptyMap())
        OrganizeSummary(found = images.size, moved = movedCount)
    }

    private fun hasMediaReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    // cleaned up duplicate query block

    private fun queryImages(): List<ImageEntry> {
        val images = mutableListOf<ImageEntry>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val dateFolderFormat = SimpleDateFormat(DATE_PATTERN, Locale.US)

        try {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex)
                    if (displayName.isNullOrBlank()) continue

                    val filePath = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                        cursor.getString(dataIndex)
                    } else {
                        null
                    }

                    if (isAlreadySorted(filePath)) continue

                    val dateMillis = if (dateTakenIndex >= 0 && !cursor.isNull(dateTakenIndex)) {
                        cursor.getLong(dateTakenIndex)
                    } else {
                        cursor.getLong(dateAddedIndex) * MILLIS_PER_SECOND
                    }

                    val id = cursor.getLong(idIndex)
                    images += ImageEntry(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        displayName = displayName,
                        mimeType = if (mimeTypeIndex >= 0) cursor.getString(mimeTypeIndex) else null,
                        dateMillis = dateMillis,
                        dateFolder = dateFolderFormat.format(Date(dateMillis)),
                        filePath = filePath,
                        sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex)
                        } else {
                            0L
                        }
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query images from MediaStore", e)
        }
        return images
    }

    private fun isAlreadySorted(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val sortedDirectory = File(picturesDirectory(), SORTED_DIR)
        return filePath.startsWith(sortedDirectory.absolutePath + File.separator)
    }

    private fun moveImage(image: ImageEntry, targetFolder: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            moveUsingMediaStore(image, targetFolder)
        } else {
            moveUsingFileSystem(image, targetFolder)
        }

    private fun moveUsingMediaStore(image: ImageEntry, targetFolder: String): Boolean {
        val relativePath = "$PICTURES_DIR/$SORTED_DIR/$targetFolder"

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            }
            if (appContext.contentResolver.update(image.uri, values, null, null) > 0) {
                return true
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "RELATIVE_PATH update rejected for '${image.displayName}'")
        }

        val copyUri = copyIntoSortedFolder(image, relativePath) ?: return false
        if (deleteOriginal(image)) {
            return true
        }

        deleteQuietly(copyUri)
        return false
    }

    private fun copyIntoSortedFolder(image: ImageEntry, relativePath: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.displayName)
            image.mimeType?.let { put(MediaStore.Images.Media.MIME_TYPE, it) }
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (image.dateMillis > 0) {
                put(MediaStore.Images.Media.DATE_TAKEN, image.dateMillis)
            }
        }

        val targetUri = appContext.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        var copied = false
        try {
            appContext.contentResolver.openInputStream(image.uri)?.use { source ->
                appContext.contentResolver.openOutputStream(targetUri)?.use { destination ->
                    source.copyTo(destination)
                    copied = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy '${image.displayName}'", e)
        }

        if (!copied) {
            deleteQuietly(targetUri)
            return null
        }

        try {
            val pendingValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            appContext.contentResolver.update(targetUri, pendingValues, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not publish the copy of '${image.displayName}'", e)
        }
        return targetUri
    }

    private fun deleteOriginal(image: ImageEntry): Boolean = try {
        appContext.contentResolver.delete(image.uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            appContext.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.d(TAG, "Cleanup of $uri was not allowed")
        }
    }

    private fun moveUsingFileSystem(image: ImageEntry, targetFolder: String): Boolean {
        val filePath = image.filePath ?: return false
        val source = File(filePath)
        if (!source.exists()) return false

        val targetDir = File(picturesDirectory(), "$SORTED_DIR/$targetFolder")
        if (!targetDir.exists() && !targetDir.mkdirs()) return false

        val target = uniqueTargetFile(targetDir, image.displayName)
        if (!source.renameTo(target)) return false

        try {
            appContext.contentResolver.delete(image.uri, null, null)
        } catch (e: SecurityException) {
            Log.d(TAG, "Stale MediaStore row for '${image.displayName}' left")
        }
        try {
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(target.absolutePath),
                arrayOf(image.mimeType),
                null
            )
        } catch (e: Exception) {
            Log.d(TAG, "MediaScanner failed for '${image.displayName}'", e)
        }
        return true
    }

    private fun uniqueTargetFile(directory: File, displayName: String): File {
        var target = File(directory, displayName)
        if (!target.exists()) return target

        val baseName = displayName.substringBeforeLast('.', displayName)
        val extension = if (displayName.contains('.')) {
            displayName.substringAfterLast('.', "")
        } else {
            ""
        }
        var counter = 1
        while (target.exists()) {
            target = if (extension.isBlank()) {
                File(directory, "$baseName ($counter)")
            } else {
                File(directory, "$baseName ($counter).$extension")
            }
            counter++
        }
        return target
    }

    private fun picturesDirectory(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    private fun targetFolderFor(image: ImageEntry, sortBy: String): String =
        if (sortBy.equals(SORT_BY_SIZE, ignoreCase = true)) {
            sizeFolder(image.sizeBytes)
        } else {
            image.dateFolder
        }

    private fun sizeFolder(sizeBytes: Long): String = when {
        sizeBytes < SMALL_IMAGE_MAX_BYTES -> SIZE_FOLDER_SMALL
        sizeBytes < MEDIUM_IMAGE_MAX_BYTES -> SIZE_FOLDER_MEDIUM
        else -> SIZE_FOLDER_LARGE
    }

    private data class ImageEntry(
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        val dateMillis: Long,
        val dateFolder: String,
        val filePath: String?,
        val sizeBytes: Long
    )

    companion object {
        const val TAG = "OrganizeImagesSkill"
        const val SORTED_DIR = "Sorted"
        val PICTURES_DIR = Environment.DIRECTORY_PICTURES
        const val DATE_PATTERN = "yyyy-MM-dd"
        const val MILLIS_PER_SECOND = 1000L
        const val SORT_BY_DATE = "date"
        const val SORT_BY_SIZE = "size"

        /** Emit a progress callback once every this many processed images. */
        const val PROGRESS_EVERY = 5
        const val SIZE_FOLDER_SMALL = "Small"
        const val SIZE_FOLDER_MEDIUM = "Medium"
        const val SIZE_FOLDER_LARGE = "Large"
        const val SMALL_IMAGE_MAX_BYTES = 1L * 1024 * 1024
        const val MEDIUM_IMAGE_MAX_BYTES = 5L * 1024 * 1024
    }
}
