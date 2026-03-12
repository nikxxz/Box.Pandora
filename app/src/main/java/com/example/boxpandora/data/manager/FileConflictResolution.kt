package com.example.boxpandora.data.manager

/**
 * What to do when a copy/move destination file already exists.
 */
enum class FileConflictResolution {
    /** Overwrite the existing file at the destination. */
    REPLACE,

    /** Keep both — append a _1 / _2 … counter to the new file's name. */
    AUTO_RENAME,

    /** Skip this file entirely; leave the existing destination untouched. */
    SKIP
}

/**
 * Describes a single pending conflict waiting for the user's decision.
 *
 * @param fileName        The bare file name that already exists at the destination.
 * @param destinationPath Full path of the destination folder.
 * @param currentIndex    1-based index of this file in the current batch.
 * @param totalCount      Total number of files in the current batch.
 */
data class PendingFileConflict(
    val fileName: String,
    val destinationPath: String,
    val currentIndex: Int,
    val totalCount: Int
)
