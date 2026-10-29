package com.propertyos.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Chooses where files are kept. {@code propertyos.storage.type} (env {@code STORAGE_TYPE}) is {@code db} by default,
 * or {@code s3} with the bucket in {@code STORAGE_BUCKET}. On AWS the client signs requests with the task role, so no keys are configured.
 */
@Configuration
public class StorageConfig {

    /** Files in S3. */
    @Bean
    @ConditionalOnProperty(name = "propertyos.storage.type", havingValue = "s3")
    public FileStore s3FileStore(@Value("${propertyos.storage.bucket:}") String bucket) {
        if (bucket.isBlank()) throw new IllegalStateException("STORAGE_TYPE=s3 needs STORAGE_BUCKET to be set.");
        return new S3FileStore(S3Client.create(), bucket);
    }

    /** Files in the database row (the default). */
    @Bean
    @ConditionalOnMissingBean(FileStore.class)
    public FileStore databaseFileStore() {
        return new DatabaseFileStore();
    }
}
