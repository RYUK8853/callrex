/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nothing design language: pure black, pure white, one red.
 * Monochrome-first; the red (#D71921) is the only accent, used sparingly for
 * recording/live state and primary actions.
 */

// ── The Nothing accent ────────────────────────────────────────────────
val NothingRed       = Color(0xFFD71921) // primary accent — recording red
val NothingRedDeep   = Color(0xFF571015) // pressed / container
val NothingRedCtr    = Color(0xFF2A0A0C) // deep container on black
val NothingRedBright = Color(0xFFFF5A60)

// ── Semantic (functional, muted) ───────────────────────────────────────────
val Success     = Color(0xFF34D399)
val Warning     = Color(0xFFFBBF24)
val InfoGray    = Color(0xFF9CA3AF)

// ── Dark scheme (Nothing is black-first) ───────────────────────────────────────
val NothingBlack      = Color(0xFF000000)
val NothingSurface    = Color(0xFF0A0A0A)
val NothingSurfaceLow = Color(0xFF050505)
val NothingSurfaceHi  = Color(0xFF141414)
val NothingSurfaceHi2 = Color(0xFF1F1F1F)
val NothingWhite      = Color(0xFFFFFFFF)
val NothingGray       = Color(0xFF8F8F8F)
val NothingLine       = Color(0xFF2A2A2A)
val NothingLineDim    = Color(0xFF1C1C1C)
val ErrorDark         = Color(0xFFFF4D4D)
val OnErrorDark       = Color(0xFF2B0505)
val ErrorCtrDark      = Color(0xFF3A0D0D)
val OnErrorCtrDark    = Color(0xFFFFD9D9)

// ── Light scheme (monochrome grey, secondary) ──────────────────────────
val NothingPaper      = Color(0xFFF5F5F5)
val NothingPaperSurf  = Color(0xFFFFFFFF)
val NothingPaperVar   = Color(0xFFE6E6E6)
val NothingInk        = Color(0xFF0A0A0A)
val NothingInkMuted   = Color(0xFF5A5A5A)
val NothingLineLight  = Color(0xFFC9C9C9)
val NothingLineLightDim = Color(0xFFDDDDDD)
val ErrorLight        = Color(0xFFB3261E)

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)
