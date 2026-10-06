package com.newendian.retichat.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DISPLAY_NAMES.md §4.2 "Learning from the channel" (narrowed 2026-10-03), and
 * the four things every client's tests assert with two devices posting as one
 * distro to one reader slot.
 */
class DistroChannelNamesTest {
    private val day = DisplayNames.CHANNEL_NAME_REFRESH_MS

    // ---- the rule itself ----

    @Test
    fun aNameEqualToTheDevicesOwnIsLearnedAtThePostsTime() {
        val learned = DisplayNames.learnedPostName("Nik", NameField.Name("Nik"), 5_000, null)
        assertEquals(DisplayNames.LearnedPostName(DisplayNames.digestHex("Nik"), 5_000), learned)
    }

    @Test
    fun aClearIsLearnedOnlyWhileTheDevicesNameIsUnset() {
        assertEquals(
            DisplayNames.LearnedPostName(DisplayNames.EMPTY_DIGEST_HEX, 9_000),
            DisplayNames.learnedPostName(null, NameField.Clear, 9_000, 1_000),
        )
        assertEquals(
            DisplayNames.LearnedPostName(DisplayNames.EMPTY_DIGEST_HEX, 9_000),
            DisplayNames.learnedPostName("", NameField.Clear, 9_000, 1_000),
        )
        assertNull(DisplayNames.learnedPostName("Nik", NameField.Clear, 9_000, 1_000))
    }

    @Test
    fun aValueTheDeviceDoesNotHoldTeachesNothing() {
        // A different name, a name while the device has none, no key 0 at all.
        assertNull(DisplayNames.learnedPostName("Nik", NameField.Name("Bea"), 9_000, null))
        assertNull(DisplayNames.learnedPostName(null, NameField.Name("Bea"), 9_000, null))
        assertNull(DisplayNames.learnedPostName("Nik", NameField.Absent, 9_000, null))
        assertNull(DisplayNames.learnedPostName(null, NameField.Absent, 9_000, null))
    }

    @Test
    fun aPostNotLaterThanWhatIsRecordedTeachesNothing() {
        assertNull(DisplayNames.learnedPostName("Nik", NameField.Name("Nik"), 5_000, 5_000))
        assertNull(DisplayNames.learnedPostName("Nik", NameField.Name("Nik"), 4_000, 5_000))
        assertEquals(
            DisplayNames.digestHex("Nik"),
            DisplayNames.learnedPostName("Nik", NameField.Name("Nik"), 5_001, 5_000)?.digestHex,
        )
    }

    // ---- two devices posting as one distro to one reader slot ----

    /** A reader's slot for the distro's channel name (§5.2 order). */
    private class Reader {
        var name: String? = null
        var at = 0L
        fun hear(field: NameField, postAt: Long) {
            when (val u = DisplayNames.acceptChannelName(name, at, field, postAt)) {
                NameUpdate.Unchanged -> Unit
                is NameUpdate.Set -> { name = u.name; at = postAt }
            }
        }
    }

    /**
     * One of the user's devices: its own name, what it last included as the
     * distro, and (with [learns]) what it learned from the distro's posts.
     * [learns] false is the mutation "learning nothing"; [learnsEverything]
     * is the mutation "learning every value".
     */
    private class Device(
        var ownName: String?,
        val learns: Boolean = true,
        val learnsEverything: Boolean = false,
    ) {
        var digest: String? = null
        var includedAt: Long? = null
        var included = 0
        var cleared = 0

        fun post(now: Long): NameField {
            val field = DisplayNames.channelPostName(ownName, digest, includedAt, newSenderSinceInclude = false, nowMs = now)
            when (field) {
                NameField.Absent -> Unit
                NameField.Clear -> { digest = DisplayNames.EMPTY_DIGEST_HEX; includedAt = now; cleared++ }
                is NameField.Name -> { digest = DisplayNames.digestHex(field.name); includedAt = now; included++ }
            }
            return field
        }

        /** The distro's post reaches this device. */
        fun hear(field: NameField, postAt: Long) {
            if (!learns) return
            if (learnsEverything) {
                val d = when (field) {
                    NameField.Absent -> return
                    NameField.Clear -> DisplayNames.EMPTY_DIGEST_HEX
                    is NameField.Name -> DisplayNames.digestHex(field.name)
                }
                if (postAt > (includedAt ?: 0L)) { digest = d; includedAt = postAt }
                return
            }
            DisplayNames.learnedPostName(ownName, field, postAt, includedAt)?.let {
                digest = it.digestHex; includedAt = it.at
            }
        }
    }

    /** [from] posts at [now]; the reader and [others] hear it. */
    private fun send(from: Device, now: Long, reader: Reader, vararg others: Device) {
        val field = from.post(now)
        reader.hear(field, now)
        others.forEach { it.hear(field, now) }
    }

    @Test
    fun aDeviceWithNoNameNeverClearsWhileItsSiblingPostsAName() {
        val reader = Reader()
        val withName = Device("Bee")
        val noName = Device(null)
        send(withName, 1_000, reader, noName)
        assertEquals("Bee", reader.name)
        send(noName, 2_000, reader, withName)
        assertEquals("the reader keeps the name", "Bee", reader.name)
        assertEquals(0, noName.cleared)
        send(noName, 3_000, reader, withName)
        assertEquals("Bee", reader.name)
    }

    @Test
    fun mutationLearningEveryValueClearsTheName() {
        val reader = Reader()
        val withName = Device("Bee")
        val noName = Device(null, learnsEverything = true)
        send(withName, 1_000, reader, noName)
        send(noName, 2_000, reader, withName)
        assertNull("learning a name it does not hold makes it clear that name", reader.name)
    }

    @Test
    fun twoDevicesWithTheSameNameIncludeItOnceWithinADay() {
        val reader = Reader()
        val a = Device("Nik")
        val b = Device("Nik")
        send(a, 1_000, reader, b)
        send(b, 2_000, reader, a)
        send(a, 3_000, reader, b)
        send(b, 4_000, reader, a)
        assertEquals("Nik", reader.name)
        assertEquals(1, a.included + b.included)
        // The refresh is the one thing that reaches silent readers, after a day.
        send(b, 1_000 + day + 1, reader, a)
        assertEquals(2, a.included + b.included)
    }

    @Test
    fun mutationLearningNothingMakesBothDevicesIncludeIt() {
        val reader = Reader()
        val a = Device("Nik", learns = false)
        val b = Device("Nik", learns = false)
        send(a, 1_000, reader, b)
        send(b, 2_000, reader, a)
        assertEquals(2, a.included + b.included)
    }

    @Test
    fun twoDevicesWithDifferentNamesIncludeTheirsOnlyOnTheirOwnTriggers() {
        val reader = Reader()
        val ann = Device("Ann")
        val bob = Device("Bob")
        send(ann, 1_000, reader, bob)
        assertEquals("Ann", reader.name)
        send(bob, 2_000, reader, ann)
        // Bob's first post as the distro is his own trigger (rule 1).
        assertEquals("Bob", reader.name)
        assertEquals(1, ann.included)
        assertEquals(1, bob.included)
        // A sibling posting never makes a device include its name again.
        send(ann, 3_000, reader, bob)
        send(bob, 4_000, reader, ann)
        send(ann, 5_000, reader, bob)
        assertEquals(1, ann.included)
        assertEquals(1, bob.included)
        // Each device's own 24-hour refresh is its trigger.
        send(ann, 1_000 + day + 1, reader, bob)
        assertEquals(2, ann.included)
        assertEquals("Ann", reader.name)
    }

    @Test
    fun mutationLearningEveryValueMakesDifferentNamesFightOnEveryPost() {
        val reader = Reader()
        val ann = Device("Ann", learnsEverything = true)
        val bob = Device("Bob", learnsEverything = true)
        send(ann, 1_000, reader, bob)
        send(bob, 2_000, reader, ann)
        send(ann, 3_000, reader, bob)
        send(bob, 4_000, reader, ann)
        assertEquals("each finds the other's name recorded and includes its own", 4, ann.included + bob.included)
    }

    @Test
    fun whenBothHaveUnsetTheirNamesOneClearIsSent() {
        val reader = Reader()
        val a = Device("Ann")
        val b = Device("Ann")
        send(a, 1_000, reader, b)
        assertEquals("Ann", reader.name)
        a.ownName = null
        b.ownName = null
        send(a, 2_000, reader, b)
        assertNull(reader.name)
        send(b, 3_000, reader, a)
        send(a, 4_000, reader, b)
        send(b, 5_000, reader, a)
        assertEquals(1, a.cleared + b.cleared)
    }

    @Test
    fun mutationLearningNothingMakesBothSendTheClear() {
        val reader = Reader()
        val a = Device("Ann", learns = false)
        val b = Device("Ann", learns = false)
        send(a, 1_000, reader, b)
        send(b, 1_500, reader, a)
        a.ownName = null
        b.ownName = null
        send(a, 2_000, reader, b)
        send(b, 3_000, reader, a)
        assertEquals(2, a.cleared + b.cleared)
    }
}
