package io.github.nezdelion.tvcast.core.http

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels

/** Random-access readable content served by [MediaServer]. */
interface ByteSource {
    /** Total size in bytes, or null when the provider cannot tell. */
    val length: Long?
    val mime: String

    /** Opens a stream positioned at [offset]. The caller closes it. */
    fun open(offset: Long): InputStream
}

class FileByteSource(private val file: File, override val mime: String) : ByteSource {
    override val length: Long get() = file.length()

    override fun open(offset: Long): InputStream {
        val input = FileInputStream(file)
        input.channel.position(offset)
        return Channels.newInputStream(input.channel)
    }
}

class ByteArraySource(private val bytes: ByteArray, override val mime: String) : ByteSource {
    override val length: Long get() = bytes.size.toLong()

    override fun open(offset: Long): InputStream =
        bytes.inputStream(offset.toInt(), bytes.size - offset.toInt())
}
