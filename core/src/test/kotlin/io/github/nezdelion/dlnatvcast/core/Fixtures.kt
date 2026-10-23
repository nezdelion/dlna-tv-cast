package io.github.nezdelion.dlnatvcast.core

object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }
}
