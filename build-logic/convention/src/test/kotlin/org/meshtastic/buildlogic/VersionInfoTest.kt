/*
 * Copyright (c) 2026 MeshPlay contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionInfoTest {
    @Test
    fun `tag refs become app display versions without the leading v`() {
        assertEquals("0.1.4", versionNameFromTagRef("refs/tags/v0.1.4"))
        assertEquals("0.1.4-rc1", versionNameFromTagRef("refs/tags/v0.1.4-rc1"))
    }

    @Test
    fun `branch refs and malformed bare tags do not override configured version`() {
        assertNull(versionNameFromTagRef("refs/heads/main"))
        assertNull(versionNameFromTagRef("refs/tags/v"))
        assertNull(versionNameFromTagRef(null))
    }
}
