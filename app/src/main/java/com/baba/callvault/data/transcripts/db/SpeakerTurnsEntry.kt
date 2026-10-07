/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Who was speaking when, for one recording.
 *
 * Read off the two capture channels *before* the mono downmix, so it costs the audio nothing — the
 * recorded file is byte-for-byte what it would have been. That downmix is deliberate and stays:
 * encoding the captured stereo split the bitrate across two channels and audibly starved the far
 * party, which a user reported. This table is how the direction information survives it.
 *
 * **Stored as A and B, never as You and Them.** Which channel carries the near party is an OEM
 * detail Android never specifies, so the mapping is learned separately and applied when a transcript
 * is *displayed*. Nothing written here can turn out to be wrong about who is who — the worst case is
 * that the labels stay neutral.
 *
 * Lives in this database rather than the recordings catalog for the reason everything here does: the
 * catalog is a rebuildable cache with a destructive fallback, and these turns cannot be recovered
 * from the finished mono file at any price. They exist only during capture.
 */
@Entity(tableName = "speaker_turns")
data class SpeakerTurnsEntry(
    /** The recording's `displayName`, this database's natural key throughout. */
    @PrimaryKey val displayName: String,
    /** `SpeakerTurnCodec` encoding: `startMs:channel` pairs joined by `;`. */
    val turns: String,
    /**
     * Whether this was an outgoing call.
     *
     * Kept because only an outgoing call can teach the channel mapping — the ringback that reveals
     * which channel is the far party plays before *we* are answered, and an incoming call has no
     * such phase. Recorded per call so the mapping can be re-derived later without guessing which
     * calls were eligible.
     */
    val outgoing: Boolean,
    /**
     * What this call's ringback suggested, as a [com.baba.callvault.data.ChannelMap] key.
     *
     * One call's observation, not the trusted answer. The trusted mapping requires two calls to
     * agree — a single noisy call must not permanently mislabel every transcript thereafter.
     */
    val observedMap: String,
    val updatedAt: Long = 0L
)
