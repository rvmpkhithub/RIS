package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * filePath is a filename relative to context.filesDir/images/ — never an absolute path or
 * content:// URI. [AD-14]
 */
@Entity(tableName = "images")
data class Image(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val active: Boolean = true,
    val uploadedAt: Long,
    val title: String? = null,
    val description: String? = null,
)
