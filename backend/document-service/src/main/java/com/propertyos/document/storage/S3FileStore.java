package com.propertyos.document.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.UUID;

/**
 * Keeps file bytes in a private S3 bucket, encrypted at rest. Objects are named {@code <company>/<area>/<id>} so
 * one company's files sit under their own prefix. Access is through the service only; the bucket has no public access.
 */
public class S3FileStore implements FileStore {
    private final S3Client s3;
    private final String bucket;

    /**
     * @param s3     the S3 client
     * @param bucket the bucket name
     */
    public S3FileStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Placement put(UUID orgId, String area, UUID id, byte[] data, String mimeType) {
        String key = orgId + "/" + area + "/" + id;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mimeType)
                .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(data));
        return new Placement(key, null);
    }

    @Override
    public byte[] read(String key, byte[] inline) {
        if (key == null) return inline; // a row written before the switch to S3
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        if (key == null) return;
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
