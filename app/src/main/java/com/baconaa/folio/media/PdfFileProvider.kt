package com.baconaa.folio.media

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

class PdfFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("附件只允许读取")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("附件只允许读取")
}
