/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import libcore.io.BufferIterator;
import libcore.io.ErrnoException;
import libcore.io.IoUtils;
import libcore.io.MemoryMappedFile;

// TODO: repackage this class, used by frameworks/base.
import org.apache.harmony.luni.internal.util.TimezoneGetter;

/**
 * A class used to initialize the time zone database. This implementation uses the
 * Olson tzdata as the source of time zone information. However, to conserve
 * disk space (inodes) and reduce I/O, all the data is concatenated into a single file,
 * with an index to indicate the starting position of each time zone record.
 *
 * @hide - used to implement TimeZone
 */
public final class ZoneInfoDB {
    private static final Object LOCK = new Object();

    /**
     * Rather than open, read, and close the big data file each time we look up a time zone,
     * we map the big data file during startup, and then just use the MemoryMappedFile.
     *
     * At the moment, this "big" data file is about 500 KiB. At some point, that will be small
     * enough that we'll just keep the byte[] in memory.
     */
    private static final MemoryMappedFile TZDATA = mapData();

    private static String version;

    /**
     * The 'ids' array contains time zone ids sorted alphabetically, for binary searching.
     * The other two arrays are in the same order. 'byteOffsets' gives the byte offset
     * of each time zone, and 'rawUtcOffsets' gives the time zone's raw UTC offset.
     */
    private static String[] ids;
    private static int[] byteOffsets;
    private static int[] rawUtcOffsets;

    static {
        readHeader();
    }

    private ZoneInfoDB() {
    }

    private static void readHeader() {
        // byte[12] tzdata_version  -- "tzdata2012f\0"
        // int index_offset
        // int data_offset
        // int zonetab_offset
        BufferIterator it = TZDATA.bigEndianIterator();

        byte[] tzdata_version = new byte[12];
        it.readByteArray(tzdata_version, 0, tzdata_version.length);
        String magic = new String(tzdata_version, 0, 6, Charsets.US_ASCII);
        if (!magic.equals("tzdata") || tzdata_version[11] != 0) {
            throw new RuntimeException("bad tzdata magic: " + Arrays.toString(tzdata_version));
        }
        version = new String(tzdata_version, 6, 5, Charsets.US_ASCII);

        int index_offset = it.readInt();
        int data_offset = it.readInt();
        int zonetab_offset = it.readInt();

        readIndex(it, index_offset, data_offset);
    }

    private static MemoryMappedFile mapData() {
        MemoryMappedFile result = mapData(System.getenv("ANDROID_DATA") + "/misc/zoneinfo/");
        if (result == null) {
            result = mapData(System.getenv("ANDROID_ROOT") + "/usr/share/zoneinfo/");
            if (result == null) {
                throw new AssertionError("Couldn't find any tzdata!");
            }
        }
        return result;
    }

    private static MemoryMappedFile mapData(String directory) {
        try {
            return MemoryMappedFile.mmapRO(directory + "tzdata");
        } catch (ErrnoException errnoException) {
            return null;
        }
    }

    private static void readIndex(BufferIterator it, int indexOffset, int dataOffset) {
        it.seek(indexOffset);

        // The database reserves 40 bytes for each id.
        final int SIZEOF_TZNAME = 40;
        // The database uses 32-bit (4 byte) integers.
        final int SIZEOF_TZINT = 4;

        byte[] idBytes = new byte[SIZEOF_TZNAME];
        int indexSize = (dataOffset - indexOffset);
        int entryCount = indexSize / (SIZEOF_TZNAME + 3*SIZEOF_TZINT);

        char[] idChars = new char[entryCount * SIZEOF_TZNAME];
        int[] idEnd = new int[entryCount];
        int idOffset = 0;

        byteOffsets = new int[entryCount];
        rawUtcOffsets = new int[entryCount];

        for (int i = 0; i < entryCount; i++) {
            it.readByteArray(idBytes, 0, idBytes.length);

            byteOffsets[i] = it.readInt();
            byteOffsets[i] += dataOffset; // TODO: change the file format so this is included.

            int length = it.readInt();
            if (length < 44) {
                throw new AssertionError("length in index file < sizeof(tzhead)");
            }
            rawUtcOffsets[i] = it.readInt();

            // Don't include null chars in the String
            int len = idBytes.length;
            for (int j = 0; j < len; j++) {
                if (idBytes[j] == 0) {
                    break;
                }
                idChars[idOffset++] = (char) (idBytes[j] & 0xFF);
            }

            idEnd[i] = idOffset;
        }

        // We create one string containing all the ids, and then break that into substrings.
        // This way, all ids share a single char[] on the heap.
        String allIds = new String(idChars, 0, idOffset);
        ids = new String[entryCount];
        for (int i = 0; i < entryCount; i++) {
            ids[i] = allIds.substring(i == 0 ? 0 : idEnd[i - 1], idEnd[i]);
        }
    }

    public static TimeZone makeTimeZone(String id) throws IOException {
        // Work out where in the big data file this time zone is.
        int index = Arrays.binarySearch(ids, id);
        if (index < 0) {
            return null;
        }

        BufferIterator it = TZDATA.bigEndianIterator();
        it.skip(byteOffsets[index]);

        return ZoneInfo.makeTimeZone(id, it);
    }

    public static String[] getAvailableIDs() {
        return ids.clone();
    }

    public static String[] getAvailableIDs(int rawOffset) {
        List<String> matches = new ArrayList<String>();
        for (int i = 0, end = rawUtcOffsets.length; i < end; ++i) {
            if (rawUtcOffsets[i] == rawOffset) {
                matches.add(ids[i]);
            }
        }
        return matches.toArray(new String[matches.size()]);
    }

    public static TimeZone getSystemDefault() {
        synchronized (LOCK) {
            TimezoneGetter tzGetter = TimezoneGetter.getInstance();
            String zoneName = tzGetter != null ? tzGetter.getId() : null;
            if (zoneName != null) {
                zoneName = zoneName.trim();
            }
            if (zoneName == null || zoneName.isEmpty()) {
                // use localtime for the simulator
                // TODO: what does that correspond to?
                zoneName = "localtime";
            }
            return TimeZone.getTimeZone(zoneName);
        }
    }

    public static String getVersion() {
        return version;
    }
}
