// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SaveFailureComposerTest {
    private static final Map<String, String> LANG = Collections.unmodifiableMap(loadLang());

    @Test
    void accessDeniedNamesTheCategoryNotJustThePath() {
        SaveFailureReason reason = SaveFailureComposer
                .describe(new AccessDeniedException("C:\\Users\\bob\\saves\\My World"));

        assertCategory(reason, "wdl.reason.access_denied", "access denied");
    }

    @Test
    void noSuchFileNamesPathNotFound() {
        SaveFailureReason reason = SaveFailureComposer.describe(new NoSuchFileException("C:\\saves\\gone"));

        assertCategory(reason, "wdl.reason.path_not_found", "path not found");
    }

    @Test
    void fileSystemExceptionWithReasonLeadsWithTheReason() {
        SaveFailureReason reason = SaveFailureComposer.describe(
                new FileSystemException("C:\\saves\\w", null, "No space left on device"));

        assertLiteral(reason, "No space left on device");
    }

    @Test
    void unmappedFileSystemExceptionFallsBackToTheSimpleName() {
        SaveFailureReason reason = SaveFailureComposer.describe(new FileSystemException("C:\\saves\\w"));

        assertLiteral(reason, "FileSystemException");
    }

    @Test
    void messageBearingExceptionCarriesItsMessage() {
        SaveFailureReason reason = SaveFailureComposer.describe(new IOException("No space left on device"));

        assertLiteral(reason, "No space left on device");
    }

    @Test
    void throwableWithNoMessageFallsBackToTheSimpleName() {
        SaveFailureReason reason = SaveFailureComposer.describe(new IOException());

        assertLiteral(reason, "IOException");
    }

    @Test
    void nullThrowableYieldsTheUnknownFallback() {
        assertCategory(SaveFailureComposer.describe(null), "wdl.reason.unknown", "unknown error");
    }

    private static void assertCategory(SaveFailureReason reason, String key, String english) {
        assertEquals(key, reason.translationKey());
        assertEquals("", reason.text());
        assertEquals(english, LANG.get(key), "en_us.json is missing or diverges on " + key);
    }

    private static void assertLiteral(SaveFailureReason reason, String text) {
        assertNull(reason.translationKey());
        assertEquals(text, reason.text());
    }

    private static Map<String, String> loadLang() {
        InputStream stream = SaveFailureComposerTest.class.getResourceAsStream("/assets/wdl/lang/en_us.json");
        if (stream == null) {
            throw new IllegalStateException("en_us.json not on the test classpath");
        }
        return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
    }
}
