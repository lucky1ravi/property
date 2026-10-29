package com.propertyos.document.storage;

import java.util.UUID;

/**
 * Keeps file bytes in the database row. This is the default, so local runs and tests need no cloud account.
 */
public class DatabaseFileStore implements FileStore {

    @Override
    public Placement put(UUID orgId, String area, UUID id, byte[] data, String mimeType) {
        return new Placement(null, data);
    }

    @Override
    public byte[] read(String key, byte[] inline) {
        if (key != null) {
            throw new IllegalStateException("File " + key + " is in object storage, but this service is set to keep files in the database. Set STORAGE_TYPE=s3.");
        }
        return inline;
    }

    @Override
    public void delete(String key) {
        // The bytes live in the row, which the caller deletes.
    }
}
