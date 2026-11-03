package com.kavach.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.kavach.core.report.ComplaintReport
import java.io.File
import java.io.RandomAccessFile

/** Minimal 16-bit PCM mono WAV writer for the Day-0 audio test. Test captures only; the app never saves call audio otherwise. */
object Wav {
    fun write(file: File, pcm: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val dataLen = pcm.size * 2
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.writeBytes("RIFF"); f.write(le32(36 + dataLen)); f.writeBytes("WAVEfmt ")
            f.write(le32(16)); f.write(le16(1)); f.write(le16(1)); f.write(le32(sampleRate)); f.write(le32(sampleRate * 2)); f.write(le16(2)); f.write(le16(16))
            f.writeBytes("data"); f.write(le32(dataLen))
            val bytes = ByteArray(dataLen)
            for (i in pcm.indices) { bytes[2 * i] = pcm[i].toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            f.write(bytes)
        }
    }
}

/** Builds the complaint PDF with Android's PdfDocument (works offline) and returns a share intent. */
object PdfExporter {
    fun share(ctx: Context, report: ComplaintReport, text: String): Intent {
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "kavach-complaint.pdf")
        write(file, text)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Cyber crime complaint: ${report.subType}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun write(file: File, text: String) {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 40
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT }
        val width = pageW - 2 * margin
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setLineSpacing(2f, 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount || pageNo == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val top = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top < pageH - 2 * margin) last++
            if (last == line) last = line + 1
            page.canvas.translate(margin.toFloat(), margin.toFloat() - top)
            page.canvas.clipRect(0f, top.toFloat(), width.toFloat(), (top + (pageH - 2 * margin)).toFloat())
            layout.draw(page.canvas)
            doc.finishPage(page)
            line = last; pageNo++
            if (line >= layout.lineCount) break
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
