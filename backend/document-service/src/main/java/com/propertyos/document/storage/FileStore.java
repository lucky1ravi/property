package com.propertyos.document.storage;

import java.util.UUID;

/**
 * Keeps the bytes of uploaded files (documents and photos).
 *
 * <p>The database always holds the metadata. Where the bytes go depends on the implementation: {@link DatabaseFileStore}
 * keeps them in the row (local runs and tests); {@link S3FileStore} puts them in a private S3 bucket and the row keeps
 * only the object key. Rows written before a switch to S3 still read correctly, because a row with no storage key is
 * served from its own bytes.
 */
public interface FileStore {

    /**
     * Where a stored file ended up.
     *
     * @param key    the object key when the bytes went to an external store, otherwise {@code null}
     * @param inline the bytes to keep in the database row, or {@code null} when they were stored externally
     */
    record Placement(String key, byte[] inline) {
    }

    /**
     * Stores a file.
     *
     * @param orgId    the company that owns the file
     * @param area     a short folder name, such as {@code documents} or {@code photos}
     * @param id       the id of the row the file belongs to
     * @param data     the file bytes
     * @param mimeType the content type
     * @return what to save in the row
     */
    Placement put(UUID orgId, String area, UUID id, byte[] data, String mimeType);

    /**
     * Reads a file back.
     *
     * @param key    the storage key saved in the row, or {@code null} for a row that holds its own bytes
     * @param inline the bytes saved in the row, or {@code null}
     * @return the file bytes
     */
    byte[] read(String key, byte[] inline);

    /**
     * Removes a stored file. Does nothing for a row that holds its own bytes.
     *
     * @param key the storage key saved in the row, or {@code null}
     */
    void delete(String key);
}
