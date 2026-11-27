// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import au.kidfin.tv.session.CorruptStateException
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerAddressTest {
    @Test fun bareHostGetsHttpAndNoTrailingSlash() {
        assertEquals("http://192.0.2.10:8096", ServerAddress.normalise(" 192.0.2.10:8096/ "))
    }

    @Test fun keepsHttpsAndBasePath() {
        assertEquals("https://media.example.org/jellyfin", ServerAddress.normalise("HTTPS://Media.Example.org/jellyfin/"))
    }

    @Test fun rejectsUnsafeOrOddInput() {
        listOf("", "ftp://x", "http://user:pw@host", "http://host/?a=1", "http://host/#f", "http://a b", "http://").forEach {
            assertNull(it, ServerAddress.normalise(it))
        }
    }
}

class ServerConfigCodecTest {
    private val config = ServerConfig(
        baseUrl = "http://192.0.2.10:8096",
        userId = "u1",
        userName = "Kids",
        deviceId = "d1",
        libraryIds = setOf("b", "a"),
        libraryNames = listOf("Kids Movies", "Kids Shows"),
    )

    @Test fun roundTrips() = assertEquals(config, ServerConfigCodec.decode(ServerConfigCodec.encode(config)))

    @Test fun neverContainsATokenField() = assertFalse(ServerConfigCodec.encode(config).contains("token", ignoreCase = true))

    @Test fun emptyLibrariesRoundTrip() {
        val c = config.copy(libraryIds = emptySet(), libraryNames = emptyList())
        assertEquals(c, ServerConfigCodec.decode(ServerConfigCodec.encode(c)))
    }

    @Test fun damagedFileIsCorrupt() {
        val text = ServerConfigCodec.encode(config).replace("Kids", "Kidz")
        try {
            ServerConfigCodec.decode(text)
            fail()
        } catch (e: CorruptStateException) {
            // expected
        }
    }
}

class SameOriginTest {
    private val base = "http://192.0.2.10:8096"

    @Test fun matchesServerOnly() {
        assertTrue(JellyfinConnection.sameOrigin(base, "http://192.0.2.10:8096/Videos/x/stream"))
        assertFalse(JellyfinConnection.sameOrigin(base, "http://192.0.2.10:8097/x"))
        assertFalse(JellyfinConnection.sameOrigin(base, "https://192.0.2.10:8096/x"))
        assertFalse(JellyfinConnection.sameOrigin(base, "http://evil.example/x"))
        assertTrue(JellyfinConnection.sameOrigin("https://a.example", "https://a.example:443/x"))
    }
}

class LibraryPagerTest {
    private val libs = mapOf("a" to (0 until 5).map { "a$it" }, "b" to (0 until 3).map { "b$it" }, "c" to emptyList())

    private suspend fun page(start: Int, limit: Int) = LibraryPager.page(
        libraries = listOf("a", "b", "c"),
        startIndex = start,
        limit = limit,
        count = { libs.getValue(it).size },
        fetch = { lib, s, n -> libs.getValue(lib).drop(s).take(n) },
    )

    @Test fun spansLibrariesInOrder() = runTest {
        val p = page(3, 4)
        assertEquals(listOf("a3", "a4", "b0", "b1"), p.items)
        assertEquals(8, p.totalCount)
    }

    @Test fun walksEveryItemExactlyOnce() = runTest {
        val seen = ArrayList<String>()
        var start = 0
        while (true) {
            val p = page(start, 3)
            seen += p.items
            start += p.items.size
            if (p.items.isEmpty() || start >= p.totalCount) break
        }
        assertEquals(libs.values.flatten(), seen)
    }

    @Test fun pastTheEndIsEmpty() = runTest {
        assertTrue(page(8, 4).items.isEmpty())
    }
}

class DeviceProfilesTest {
    private val chromecastLike = CodecSupport(
        videoCodecs = setOf("h264", "hevc", "vp9"),
        audioCodecs = setOf("aac", "mp3", "ac3", "eac3", "opus"),
        hevc10Bit = true,
        dolbyVision = false,
        maxWidth = 3840,
        maxHeight = 2160,
    )

    @Test fun trueHdIsNotDirectPlayedUnlessSupported() {
        val p = DeviceProfiles.build(chromecastLike)
        val audio = p.directPlayProfiles.single().audioCodec!!.split(',')
        assertFalse("truehd" in audio)
        assertTrue("eac3" in audio)
        // Video is copied while audio is transcoded: HEVC must be allowed in the HLS profile.
        assertEquals("hevc,h264", p.transcodingProfiles.single().videoCodec)
    }

    @Test fun trueHdPassthroughIsUsedWhenTheTvTakesIt() {
        val p = DeviceProfiles.build(chromecastLike.copy(audioCodecs = chromecastLike.audioCodecs + "truehd"))
        assertTrue("truehd" in p.directPlayProfiles.single().audioCodec!!.split(','))
    }

    @Test fun noHevc10MeansNoHdrDirectPlay() {
        val p = DeviceProfiles.build(chromecastLike.copy(hevc10Bit = false))
        val hevc = p.codecProfiles.single { it.codec == "hevc" }
        assertEquals("8", hevc.conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.value)
        assertEquals("SDR", hevc.conditions.single { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }.value)
    }

    @Test fun plainDolbyVisionOnlyWithADecoder() {
        val range = { caps: CodecSupport ->
            DeviceProfiles.build(caps).codecProfiles.single { it.codec == "hevc" }
                .conditions.single { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }.value!!.split('|')
        }
        assertFalse("DOVI" in range(chromecastLike))
        assertTrue("DOVI" in range(chromecastLike.copy(dolbyVision = true)))
    }

    @Test fun noSubtitleProfiles() = assertTrue(DeviceProfiles.build(chromecastLike).subtitleProfiles.isEmpty())
}
