// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.ui.AttachmentLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure constants / contracts for the image attachment path. Bitmap decode
 * requires an Android runtime (device / instrumented); camera capture is
 * manual on-device (TakePicture + zero-persist delete).
 */
class AttachmentLoaderImageTest {

    @Test
    fun `camera staging dir name is stable for FileProvider paths`() {
        assertEquals("cameracapture", AttachmentLoader.CAMERA_CAPTURE_DIR)
    }

    @Test
    fun `attachment size cap is positive and matches control payload`() {
        assertTrue(AttachmentLoader.MAX_ATTACHMENT_BYTES > 0)
        assertEquals(
            com.zitrone.app.data.AttachmentControlPayload.ATTACHMENT_MAX_BYTES,
            AttachmentLoader.MAX_ATTACHMENT_BYTES,
        )
    }
}
