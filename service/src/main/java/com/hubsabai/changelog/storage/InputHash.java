package com.hubsabai.changelog.storage;

import com.hubsabai.changelog.ai.ReleaseNoteEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class InputHash {

    public static String of(List<ReleaseNoteEntry> entries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (ReleaseNoteEntry entry : entries) {
                md.update(str(entry.title()));
                md.update(str(entry.description()));
                md.update(str(entry.type()));
                md.update(str(entry.scope()));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] str(String s) {
        return s != null ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }
}
