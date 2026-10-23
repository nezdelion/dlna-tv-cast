package io.github.nezdelion.dlnatvcast.core.cast

object ShareIntake {
    /**
     * Keeps the items the receiver may read, in the order the sharing app gave them, and counts
     * the rest. Only readable items can be forwarded with a grant; the others are reported as
     * skipped.
     */
    fun <T> splitReadable(items: List<T>, canRead: (T) -> Boolean): Pair<List<T>, Int> {
        val readable = items.filter(canRead)
        return readable to items.size - readable.size
    }
}
