/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

public class DownloadRequest {
    public String url;
    public String subFolder;
    public String fileName;
    /** Publication time in milliseconds, or 0 to leave the file's own timestamps alone. */
    public long publishedTimeMillis;
    /** Skip the download when the target filename is already present in the folder. */
    public boolean skipIfExists;

    public DownloadRequest(String url, String subFolder, String fileName) {
        this(url, subFolder, fileName, 0L, true);
    }

    public DownloadRequest(String url, String subFolder, String fileName, long publishedTimeMillis, boolean skipIfExists) {
        this.url = url;
        this.subFolder = subFolder;
        this.fileName = fileName;
        this.publishedTimeMillis = publishedTimeMillis;
        this.skipIfExists = skipIfExists;
    }
}