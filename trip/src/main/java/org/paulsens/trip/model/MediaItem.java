package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

/**
 * One managed media file: a travel guide, a flyer, a rotating home-page image. The bytes live in S3 and are
 * served by CloudFront; this row is the metadata that makes the file <em>managed</em> rather than merely
 * uploaded -- it is what lets a page render "the current home-page images" from data instead of from hardcoded
 * markup, and what gives an admin something to list, describe and retire.
 *
 * <p>Theme assets (icons, backgrounds) deliberately have no row here: they stay in the container image, where
 * they version with the code that references them.
 *
 * <p>{@link #s3Key} is the object key and the file's identity in the URL; {@link #id} is the row key. They are
 * separate so a file can be renamed or replaced without invalidating references held elsewhere.
 */
@Value
public class MediaItem implements Serializable {

    /** Row identity (a UUID). Stable across renames of the underlying object. */
    String id;
    /** The S3 object key, e.g. {@code downloads/MedjugorjeTravelGuide2026Apr.pdf}. */
    String s3Key;
    String title;
    String description;
    String contentType;
    long size;
    /**
     * Where the site should use this file, e.g. {@code homepage} for rotating images, or {@code downloads} for
     * the documents list. Null means stored but not placed -- uploaded and available by URL, on no page.
     */
    String slot;
    /** Orders items within a slot; lower first. Ties fall back to upload time. */
    int position;
    LocalDateTime uploaded;
    String uploadedBy;
    /**
     * Object key of a reduced display rendition, when one exists (chat photos store an 800px-wide copy
     * beside the original). Null -- every pre-existing row and most uploads -- means there is only the one
     * object; use {@link #getDisplayKey()} to render without caring which.
     */
    String smallKey;
    /**
     * True when this item must not appear on public pages (the landing-page galleria and any future public
     * listing). Stored inverted -- "hidden", not "public" -- so every pre-existing row (null) reads as
     * visible, which is the required default; a primitive named the other way would have read every old row
     * as hidden. Members-only pages (the trip album) still show hidden items. Read via {@link #getHidden()}.
     */
    @Getter(AccessLevel.NONE)
    Boolean hidden;

    @JsonCreator
    public MediaItem(
            @JsonProperty("id") final String id,
            @JsonProperty("s3Key") final String s3Key,
            @JsonProperty("title") final String title,
            @JsonProperty("description") final String description,
            @JsonProperty("contentType") final String contentType,
            @JsonProperty("size") final long size,
            @JsonProperty("slot") final String slot,
            @JsonProperty("position") final int position,
            @JsonProperty("uploaded") final LocalDateTime uploaded,
            @JsonProperty("uploadedBy") final String uploadedBy,
            @JsonProperty("smallKey") final String smallKey,
            @JsonProperty("hidden") final Boolean hidden) {
        this.id = id;
        this.s3Key = s3Key;
        this.title = title;
        this.description = description;
        this.contentType = contentType;
        this.size = size;
        this.slot = (slot == null || slot.isBlank()) ? null : slot.trim();
        this.position = position;
        this.uploaded = uploaded;
        this.uploadedBy = uploadedBy;
        this.smallKey = (smallKey == null || smallKey.isBlank() || smallKey.equals(s3Key)) ? null : smallKey;
        // FALSE normalizes to null so visible rows stay compact in JSON and equality is stable across a
        // serialization round trip.
        this.hidden = Boolean.TRUE.equals(hidden) ? Boolean.TRUE : null;
    }

    /** Compatibility constructor predating the {@link #hidden} flag. */
    public MediaItem(final String id, final String s3Key, final String title, final String description,
            final String contentType, final long size, final String slot, final int position,
            final LocalDateTime uploaded, final String uploadedBy, final String smallKey) {
        this(id, s3Key, title, description, contentType, size, slot, position, uploaded, uploadedBy, smallKey,
                null);
    }

    /** Compatibility constructor for the common single-rendition case. */
    public MediaItem(final String id, final String s3Key, final String title, final String description,
            final String contentType, final long size, final String slot, final int position,
            final LocalDateTime uploaded, final String uploadedBy) {
        this(id, s3Key, title, description, contentType, size, slot, position, uploaded, uploadedBy, null,
                null);
    }

    /**
     * Whether this item is excluded from public pages. Hand-written so the bean property is a plain
     * {@code boolean}: a {@code Boolean} getter would hand EL a null for every pre-existing row, and null
     * reads as false only by accident of the call site.
     */
    public boolean getHidden() {
        return Boolean.TRUE.equals(hidden);
    }

    /** @return a copy of this item with {@link #hidden} set as given; all other fields unchanged. */
    @JsonIgnore
    public MediaItem withHidden(final boolean nowHidden) {
        return new MediaItem(id, s3Key, title, description, contentType, size, slot, position, uploaded,
                uploadedBy, smallKey, nowHidden);
    }

    /** @return the key a page should display: the reduced rendition when there is one, else the object. */
    @JsonIgnore
    public String getDisplayKey() {
        return smallKey == null ? s3Key : smallKey;
    }

    /** @return a human-friendly size, for the admin list. */
    @JsonIgnore
    public String getDisplaySize() {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.0f KB", size / 1024.0);
        }
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }
}
