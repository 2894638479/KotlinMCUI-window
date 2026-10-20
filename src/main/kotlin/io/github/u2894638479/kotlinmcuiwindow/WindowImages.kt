package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock

/**
 * The local images this process has decoded, one entry per file, read off the session thread.
 *
 * `localImage` is called while a frame is being built, and the platform contract forbids waiting for
 * io there. So the first ask for a file only records that it is wanted and answers `empty`; a
 * background thread reads and decodes it, and the result is published back to the session thread -
 * the AWT event thread here. Later asks hit the entry and answer from memory.
 *
 * An entry carries both halves of what a file means to this backend: [Entry.holder] is what the
 * framework lays out with, [Entry.pixels] is what the renderer uploads. One entry is also what makes
 * [forget] and [sweep] honest - dropping it drops the size and the pixels together.
 *
 * Entries age. A file that has not been asked for during [TTL_MILLIS] is dropped by [sweep], which
 * the window runs once per frame. Only the session thread touches [entries]: the background thread
 * hands its result to [publish] instead of writing the map itself, so no concurrent container is
 * needed.
 */
internal object WindowImages {
    /** How long an entry may go unused before [sweep] drops it: a minute, at 60 frames a second. */
    internal const val TTL_MILLIS = 60_000L

    /** How long [reset] gives the reads in flight before it calls the wait off and fails. */
    private const val SETTLE_TIMEOUT_MILLIS = 10_000L

    internal class Entry(val file: File) {
        /** When this entry was last asked for, measured by [clock]. */
        var lastUsed: Long = 0L

        /** What the framework draws with; `null` while the read is in flight or after it failed. */
        var holder: ImageHolder? = null

        /** The decoded image the renderer uploads; `null` until the read has finished. */
        var pixels: BufferedImage? = null

        /** Set from scheduling the read until its result has been published. */
        var loading: Boolean = false

        /** A read finished without pixels; the file is not read again until the entry is forgotten. */
        var failed: Boolean = false
    }

    private val entries = HashMap<File, Entry>()

    /** Reads whose task has not returned yet, so [reset] knows whether any read is still running. */
    private val inFlight = AtomicInteger()

    /** Guards the wait in [awaitReads]; a finishing read signals it. */
    private val readsSettled = ReentrantLock()

    /** Signalled under [readsSettled] whenever a read returns. */
    private val noReads = readsSettled.newCondition()

    /** Wall clock in milliseconds; tests replace it to age entries without sleeping. */
    internal var clock: () -> Long = WALL_CLOCK

    /**
     * Where a read runs. One thread is enough - decoding a UI image is quick and the session thread
     * never waits for it - and the thread is a daemon, so a queued read cannot keep the process alive.
     */
    internal var executor: Executor = DEFAULT_EXECUTOR

    /**
     * How a finished read returns to the session thread. Production queues it on the event dispatch
     * thread; a test replaces it to run the publication inline and keep the sequence deterministic.
     */
    internal var publish: ((() -> Unit) -> Unit) = EDT_PUBLISH

    /** How a file becomes pixels; a test replaces it to make a read slow or to inject a failure. */
    internal var decode: (File) -> BufferedImage? = IMAGE_IO_DECODE

    /**
     * The holder for [file], starting a background read the first time the file is asked for.
     *
     * Never reads the disk on the calling thread: an entry that is not ready answers
     * [ImageHolder.empty], which is the framework's "nothing to draw yet". A failed read keeps
     * answering empty rather than retrying every frame; [forget] is how a caller says it changed.
     */
    fun holder(file: File): ImageHolder {
        val now = clock()
        val entry = entries[file]
        if (entry != null) {
            entry.lastUsed = now
            entry.holder?.let { return it }
            // A read already on its way, or one that failed, has nothing new to say this frame.
            if (entry.loading || entry.failed) return ImageHolder.empty
            read(entry)
            return ImageHolder.empty
        }
        val created = Entry(file).also {
            it.lastUsed = now
            entries[file] = it
        }
        read(created)
        return ImageHolder.empty
    }

    /**
     * The pixels decoded for [file], or `null` while they have not arrived. Never reads the disk.
     *
     * Asking for the pixels is a use like any other: a scene that keeps drawing a holder without
     * calling [holder] again must not lose the texture to the sweep underneath it.
     */
    fun pixels(file: File): BufferedImage? {
        val entry = entries[file] ?: return null
        entry.lastUsed = clock()
        return entry.pixels
    }

    /** Forget [file] entirely: the next [holder] reads it again. */
    fun forget(file: File) {
        entries.remove(file)
    }

    /**
     * Drop entries that have gone unused for longer than [TTL_MILLIS].
     *
     * Called once per frame from the window. A load in flight is skipped, so the sweep cannot drop
     * the entry whose result is about to be published.
     */
    fun sweep() {
        val now = clock()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.loading) continue
            if (now - entry.lastUsed > TTL_MILLIS) iterator.remove()
        }
    }

    /**
     * Run [read] off the session thread, then hand its result back through [publish].
     *
     * The wiring is taken now rather than when the read finishes: a read belongs to whatever asked
     * for it, and the queue or thread it reports to cannot change under it. Only tests replace any
     * of this, and a read that outlived the case that scheduled it must not be adopted by the next
     * one - it would answer into a queue it has nothing to do with.
     *
     * The renderer's resource textures use this same route: a texture is either decoded already or
     * on its way, never read on the thread that paints.
     */
    fun loadLater(read: () -> BufferedImage?, published: (BufferedImage?) -> Unit) {
        val deliver = publish
        inFlight.incrementAndGet()
        try {
            executor.execute {
                try {
                    // A throwing read is a source that could not be decoded, not a broken frame.
                    val image = runCatching(read).getOrNull()
                    deliver { published(image) }
                } finally {
                    readFinished()
                }
            }
        } catch (t: Throwable) {
            readFinished()
            throw t
        }
    }

    /**
     * Restore the production wiring and empty the cache; tests start each case from a known state.
     *
     * A read that was already scheduled cannot be cancelled - the executor has no handle for that -
     * so this waits for it to finish before returning. A reset that returned while the previous
     * case's read was still running would leave that read in the middle of the next case, and a
     * test that is supposed to own its executor and publication would be sharing them.
     */
    internal fun reset() {
        entries.clear()
        clock = WALL_CLOCK
        executor = DEFAULT_EXECUTOR
        publish = EDT_PUBLISH
        decode = IMAGE_IO_DECODE
        awaitReads()
    }

    /** Wait until no read is running, or fail loudly: a read that never settles breaks the reset. */
    private fun awaitReads() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MILLIS)
        readsSettled.withLock {
            while (true) {
                if (inFlight.get() == 0) return
                val remaining = deadline - System.nanoTime()
                check(remaining > 0L) {
                    "a local image read was still running ${SETTLE_TIMEOUT_MILLIS}ms after the cache was reset"
                }
                noReads.awaitNanos(remaining)
            }
        }
    }

    private fun readFinished() {
        inFlight.decrementAndGet()
        readsSettled.withLock { noReads.signalAll() }
    }

    private fun read(entry: Entry) {
        entry.loading = true
        entry.failed = false
        // The decoder the entry was scheduled under; a later replacement is another reader's.
        val decode = decode
        loadLater(
            read = { decode(entry.file) },
            published = { image ->
                // A reload or a sweep may have dropped the entry while the read was in flight; a
                // late result must not put back what the caller already let go of.
                if (entries[entry.file] === entry) {
                    entry.loading = false
                    entry.failed = image == null
                    entry.pixels = image
                    entry.holder = image?.let {
                        ImageHolder(ImageSource.LocalFile(entry.file), it.width.px, it.height.px)
                    }
                    entry.lastUsed = clock()
                }
            },
        )
    }
}

private val WALL_CLOCK: () -> Long = System::currentTimeMillis

/**
 * Where a finished read goes: the event dispatch thread, inline when it is already there.
 *
 * The window paints and calls `localImage` on that thread, so "publish back to the session thread"
 * and "queue on the EDT" are the same statement.
 */
private val EDT_PUBLISH: ((() -> Unit) -> Unit) = { block ->
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}

private val DEFAULT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "kotlinmcuiwindow-image").apply { isDaemon = true }
}

private val IMAGE_IO_DECODE: (File) -> BufferedImage? = { file ->
    runCatching { ImageIO.read(file) }.getOrNull()
}
