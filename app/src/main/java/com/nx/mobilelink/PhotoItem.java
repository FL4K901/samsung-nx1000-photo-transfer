/*
 * Copyright (c) 2026 FL4K901. All rights reserved.
 * SPDX-License-Identifier: MIT
 *
 * This software is licensed under the MIT License.
 * See LICENSE file in the project root for full license terms.
 */

package com.nx.mobilelink;

import android.graphics.Bitmap;

public class PhotoItem {
    public static final int STATUS_NONE = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_ERROR = -1;

    private String id;
    private String title;
    private String date;
    private long sizeBytes;
    private String thumbUrl;
    private String fullUrl;
    private boolean isVideo;
    private boolean selected;
    private int downloadStatus = STATUS_NONE;
    private transient Bitmap cachedBitmap;

    public PhotoItem() {}

    public PhotoItem(String id, String title, String date, long sizeBytes, String thumbUrl, String fullUrl, boolean isVideo) {
        this.id = id;
        this.title = title;
        this.date = date;
        this.sizeBytes = sizeBytes;
        this.thumbUrl = thumbUrl;
        this.fullUrl = fullUrl;
        this.isVideo = isVideo;
        this.selected = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getFormattedSize() {
        if (sizeBytes <= 0) return "";
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", sizeBytes / 1024.0);
        }
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }

    public String getThumbUrl() { return thumbUrl; }
    public void setThumbUrl(String thumbUrl) { this.thumbUrl = thumbUrl; }

    public String getFullUrl() { return fullUrl; }
    public void setFullUrl(String fullUrl) { this.fullUrl = fullUrl; }

    public boolean isVideo() { return isVideo; }
    public void setVideo(boolean video) { isVideo = video; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public int getDownloadStatus() { return downloadStatus; }
    public void setDownloadStatus(int downloadStatus) { this.downloadStatus = downloadStatus; }

    public Bitmap getCachedBitmap() { return cachedBitmap; }
    public void setCachedBitmap(Bitmap cachedBitmap) { this.cachedBitmap = cachedBitmap; }
}
