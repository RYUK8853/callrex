/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.net.Uri
import androidx.core.net.toUri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A bulk delete removes files the user cannot get back, and one row is not always one file — a
 * recording kept both on the device and in Drive is a single row carrying two URIs. Every case here
 * is one a wrong answer would destroy something.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14 tops out at SDK 35 while the app targets 36, same as CallLogReaderTest.
@Config(sdk = [35])
class RecordingSelectionTest {

    private fun device(name: String) = item(name, RecordingSource.LOCAL, local = "content://dev/$name")
    private fun drive(name: String) = item(name, RecordingSource.DRIVE, drive = "content://drv/$name")
    private fun both(name: String) =
        item(name, RecordingSource.BOTH, local = "content://dev/$name", drive = "content://drv/$name")

    private fun item(name: String, source: RecordingSource, local: String? = null, drive: String? = null) =
        RecordingItem(
            uri = (local ?: drive)!!.toUri(),
            displayName = name,
            sizeBytes = 1,
            lastModified = 0,
            direction = null,
            displayDate = null,
            startedAtMillis = null,
            number = null,
            source = source,
            localUri = local?.toUri(),
            driveUri = drive?.toUri(),
        )

    // ---- When to ask ----

    @Test
    fun `asks which copies only when a two-copy recording is selected`() {
        assertTrue(RecordingSelection.needsScopeChoice(listOf(device("a"), both("b"))))
    }

    @Test
    fun `does not ask when every selected recording exists in one place`() {
        // A dialog in front of an unambiguous action is just an extra tap.
        assertFalse(RecordingSelection.needsScopeChoice(listOf(device("a"), drive("b"))))
        assertFalse(RecordingSelection.needsScopeChoice(emptyList()))
    }

    // ---- What gets deleted ----

    @Test
    fun `device-only scope removes just the device copy of a two-copy recording`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.DEVICE)
        assertEquals(listOf("content://dev/a".toUri()), uris)
    }

    @Test
    fun `drive-only scope removes just the Drive copy`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.DRIVE)
        assertEquals(listOf("content://drv/a".toUri()), uris)
    }

    @Test
    fun `both scope removes each copy exactly once`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.BOTH)
        assertEquals(listOf("content://dev/a".toUri(), "content://drv/a".toUri()), uris)
    }

    @Test
    fun `device-only scope never touches a Drive-only recording`() {
        // THE CASE THAT NEARLY COST A FILE. This asserted the opposite until 2026-07-31: the scope
        // governed two-copy recordings only, and a Drive-only recording was deleted whatever was
        // chosen. Picking the most restrictive option must not destroy a file that option says
        // nothing about — when a delete is ambiguous, the reading that removes less wins.
        val uris = RecordingSelection.urisToDelete(listOf(drive("a")), DeleteScope.DEVICE)
        assertEquals(emptyList<Uri>(), uris)
    }

    @Test
    fun `drive-only scope never touches a device-only recording`() {
        val uris = RecordingSelection.urisToDelete(listOf(device("a")), DeleteScope.DRIVE)
        assertEquals(emptyList<Uri>(), uris)
    }

    @Test
    fun `a mixed selection deletes only what the scope names`() {
        // device("a") has no Drive copy, so "Drive only" leaves it entirely alone.
        val uris = RecordingSelection.urisToDelete(listOf(device("a"), both("b")), DeleteScope.DRIVE)
        assertEquals(listOf("content://drv/b".toUri()), uris)
    }

    // ---- Telling the user what will survive ----

    @Test
    fun `counts how many recordings a scope actually affects`() {
        val selection = listOf(drive("a"), both("b"), both("c"))
        assertEquals(2, RecordingSelection.affectedCount(selection, DeleteScope.DEVICE))
        assertEquals(3, RecordingSelection.affectedCount(selection, DeleteScope.DRIVE))
        assertEquals(3, RecordingSelection.affectedCount(selection, DeleteScope.BOTH))
    }

    @Test
    fun `names the recordings a scope would leave alone`() {
        // The exact selection from the device on 2026-07-31: one Drive-only and two held in both
        // places. "Device only" keeps the Drive-only one, and the dialog has to say so.
        val selection = listOf(drive("Feroza"), both("b"), both("c"))
        assertEquals(listOf("Feroza"), RecordingSelection.skipped(selection, DeleteScope.DEVICE).map { it.displayName })
        assertEquals(emptyList<String>(), RecordingSelection.skipped(selection, DeleteScope.DRIVE).map { it.displayName })
        assertEquals(emptyList<String>(), RecordingSelection.skipped(selection, DeleteScope.BOTH).map { it.displayName })
    }

    @Test
    fun `both copies never skips anything`() {
        val selection = listOf(device("a"), drive("b"), both("c"))
        assertEquals(emptyList<RecordingItem>(), RecordingSelection.skipped(selection, DeleteScope.BOTH))
        assertEquals(3, RecordingSelection.affectedCount(selection, DeleteScope.BOTH))
    }

    @Test
    fun `deleting nothing is not an error`() {
        assertEquals(emptyList<Uri>(), RecordingSelection.urisToDelete(emptyList(), DeleteScope.BOTH))
    }

    // ---- What gets shared ----

    @Test
    fun `shares one file per recording, preferring the device copy`() {
        // Attaching both copies would send the same call twice.
        val uris = RecordingSelection.urisToShare(listOf(both("a"), drive("b")))
        assertEquals(listOf("content://dev/a".toUri(), "content://drv/b".toUri()), uris)
    }
}
