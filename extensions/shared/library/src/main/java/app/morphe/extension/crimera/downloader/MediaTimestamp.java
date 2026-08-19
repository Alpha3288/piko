/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.crimera.PikoUtils;

public class MediaTimestamp {
    // The mp4 clock counts seconds from 1904-01-01, the unix clock from 1970-01-01.
    private static final long MP4_EPOCH_OFFSET_SECONDS = 2082844800L;

    private static final String EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss";

    /** Absolute read and write at a byte offset. Backed by Os.pread in the app and by a file in the offline check. */
    interface ByteIo {
        void read(long offset, byte[] dst, int len) throws Exception;

        void write(long offset, byte[] src, int len) throws Exception;
    }

    public static void apply(Context context, Uri documentUri, String fileName, long publishedTimeMillis) {
        if (publishedTimeMillis <= 0 || documentUri == null || fileName == null) {
            return;
        }

        String lowerName = fileName.toLowerCase(Locale.US);
        try {
            if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                setExifDate(context, documentUri, publishedTimeMillis);
            } else if (lowerName.endsWith(".mp4")) {
                setMp4Date(context, documentUri, publishedTimeMillis);
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }

        touchFile(documentUri, publishedTimeMillis);
    }

    private static void setExifDate(Context context, Uri documentUri, long publishedTimeMillis) throws Exception {
        // ExifInterface can only take a descriptor from API 24.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(documentUri, "rw")) {
            if (descriptor == null) {
                return;
            }
            // One zone drives both the wall-clock string and the declared offset so the two cannot disagree.
            TimeZone zone = TimeZone.getDefault();
            SimpleDateFormat format = new SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US);
            format.setTimeZone(zone);
            String value = format.format(new Date(publishedTimeMillis));
            String offset = formatUtcOffset(publishedTimeMillis, zone);

            ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, value);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, value);
            exif.setAttribute(ExifInterface.TAG_DATETIME, value);
            // A timestamp with no offset is ambiguous and some readers assume UTC.
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset);
            exif.saveAttributes();
        }
    }

    /** EXIF offset for the zone as it stood at that instant, "+HH:MM" or "-HH:MM". */
    static String formatUtcOffset(long millis, TimeZone zone) {
        int minutes = zone.getOffset(millis) / 60000;
        char sign = minutes < 0 ? '-' : '+';
        int magnitude = Math.abs(minutes);
        return String.format(Locale.US, "%c%02d:%02d", sign, magnitude / 60, magnitude % 60);
    }

    private static void setMp4Date(Context context, Uri documentUri, long publishedTimeMillis) throws Exception {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(documentUri, "rw")) {
            if (descriptor == null) {
                return;
            }
            final FileDescriptor fd = descriptor.getFileDescriptor();
            long length = descriptor.getStatSize();
            if (length <= 0) {
                return;
            }
            setMp4CreationTime(new ByteIo() {
                @Override
                public void read(long offset, byte[] dst, int len) throws Exception {
                    int done = 0;
                    while (done < len) {
                        int count = Os.pread(fd, dst, done, len - done, offset + done);
                        if (count <= 0) {
                            throw new IOException("short read");
                        }
                        done += count;
                    }
                }

                @Override
                public void write(long offset, byte[] src, int len) throws Exception {
                    int done = 0;
                    while (done < len) {
                        int count = Os.pwrite(fd, src, done, len - done, offset + done);
                        if (count <= 0) {
                            throw new IOException("short write");
                        }
                        done += count;
                    }
                }
            }, length, publishedTimeMillis / 1000L);
        }
    }

    static boolean setMp4CreationTime(ByteIo io, long fileLength, long unixSeconds) throws Exception {
        long[] moov = findBox(io, 0, fileLength, "moov");
        if (moov == null) {
            return false;
        }
        long[] mvhd = findBox(io, moov[0], moov[1], "mvhd");
        if (mvhd == null) {
            return false;
        }

        byte[] version = new byte[1];
        io.read(mvhd[0], version, 1);
        long mp4Seconds = unixSeconds + MP4_EPOCH_OFFSET_SECONDS;
        long payloadSize = mvhd[1] - mvhd[0];

        // The payload must be able to hold the fields this version writes, or the write would
        // land past this box: into a sibling box, past moov, or past the end of the file.
        if (version[0] == 1) {
            if (payloadSize < 20) {
                return false;
            }
            writeUint64(io, mvhd[0] + 4, mp4Seconds);
            writeUint64(io, mvhd[0] + 12, mp4Seconds);
        } else if (version[0] == 0) {
            if (payloadSize < 16) {
                return false;
            }
            writeUint32(io, mvhd[0] + 4, mp4Seconds);
            writeUint32(io, mvhd[0] + 8, mp4Seconds);
        } else {
            // Only versions 0 and 1 are defined by ISO/IEC 14496-12.
            return false;
        }
        return true;
    }

    /** Returns {payloadStart, payloadEnd} of the first child box of the given type, or null. */
    private static long[] findBox(ByteIo io, long start, long end, String type) throws Exception {
        byte[] header = new byte[8];
        long offset = start;
        while (offset + 8 <= end) {
            io.read(offset, header, 8);
            long size = readUint32(header, 0);
            String boxType = new String(header, 4, 4, StandardCharsets.US_ASCII);
            long payloadStart = offset + 8;

            if (size == 1) {
                // A size of 1 means the real 64-bit size follows the type.
                if (offset + 16 > end) {
                    return null;
                }
                io.read(offset + 8, header, 8);
                size = readUint64(header, 0);
                payloadStart = offset + 16;
            } else if (size == 0) {
                // A size of 0 means the box runs to the end of the container.
                size = end - offset;
            }

            // Compares as size > end - offset rather than offset + size > end: offset is bounded
            // by end and never overflows, but a maliciously large 64-bit size added to offset can.
            if (size < payloadStart - offset || size > end - offset) {
                return null;
            }
            if (boxType.equals(type)) {
                return new long[]{payloadStart, offset + size};
            }
            offset += size;
        }
        return null;
    }

    private static long readUint32(byte[] buffer, int index) {
        return ((long) (buffer[index] & 0xff) << 24)
                | ((long) (buffer[index + 1] & 0xff) << 16)
                | ((long) (buffer[index + 2] & 0xff) << 8)
                | (buffer[index + 3] & 0xff);
    }

    private static long readUint64(byte[] buffer, int index) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buffer[index + i] & 0xff);
        }
        return value;
    }

    private static void writeUint32(ByteIo io, long offset, long value) throws Exception {
        byte[] out = new byte[4];
        for (int i = 3; i >= 0; i--) {
            out[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        io.write(offset, out, 4);
    }

    private static void writeUint64(ByteIo io, long offset, long value) throws Exception {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        io.write(offset, out, 8);
    }

    // MediaProvider's FUSE layer refuses this for files the app does not own, so it only lands
    // on pre-Android-11 devices. The container metadata above is the reliable path.
    private static void touchFile(Uri documentUri, long publishedTimeMillis) {
        try {
            String documentId = DocumentsContract.getDocumentId(documentUri);
            int separator = documentId.indexOf(':');
            if (separator < 0) {
                return;
            }
            String volume = documentId.substring(0, separator);
            String relativePath = documentId.substring(separator + 1);
            File root = "primary".equals(volume)
                    ? Environment.getExternalStorageDirectory()
                    : new File("/storage/" + volume);
            new File(root, relativePath).setLastModified(publishedTimeMillis);
        } catch (Exception ignored) {
        }
    }
}
