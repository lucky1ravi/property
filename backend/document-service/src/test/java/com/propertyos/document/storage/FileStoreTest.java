package com.propertyos.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStoreTest {
    private static final byte[] BYTES = {1, 2, 3};

    @Test
    @DisplayName("the database store keeps the bytes in the row and has no key")
    void databaseStoreKeepsBytesInline() {
        FileStore store = new DatabaseFileStore();

        FileStore.Placement placed = store.put(UUID.randomUUID(), "documents", UUID.randomUUID(), BYTES, "text/plain");

        assertThat(placed.key()).isNull();
        assertThat(placed.inline()).isEqualTo(BYTES);
        assertThat(store.read(null, BYTES)).isEqualTo(BYTES);
        store.delete(null); // nothing to remove
    }

    @Test
    @DisplayName("the database store explains itself when a row points to S3")
    void databaseStoreRefusesObjectKeys() {
        assertThatThrownBy(() -> new DatabaseFileStore().read("org/photos/x", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STORAGE_TYPE=s3");
    }

    @Test
    @DisplayName("the S3 store writes an encrypted object under the company prefix and keeps no bytes in the row")
    void s3StorePutsEncryptedObject() {
        S3Client s3 = Mockito.mock(S3Client.class);
        UUID org = UUID.randomUUID(), id = UUID.randomUUID();

        FileStore.Placement placed = new S3FileStore(s3, "files").put(org, "photos", id, BYTES, "image/png");

        assertThat(placed.key()).isEqualTo(org + "/photos/" + id);
        assertThat(placed.inline()).isNull();
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().bucket()).isEqualTo("files");
        assertThat(req.getValue().key()).isEqualTo(placed.key());
        assertThat(req.getValue().contentType()).isEqualTo("image/png");
        assertThat(req.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    @DisplayName("the S3 store reads by key, and serves rows that predate S3 from their own bytes")
    void s3StoreReads() {
        S3Client s3 = Mockito.mock(S3Client.class);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), BYTES));
        FileStore store = new S3FileStore(s3, "files");

        assertThat(store.read("org/photos/x", null)).isEqualTo(BYTES);
        assertThat(store.read(null, new byte[]{9})).isEqualTo(new byte[]{9});
        verify(s3, times(1)).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("the S3 store deletes by key and ignores rows without one")
    void s3StoreDeletes() {
        S3Client s3 = Mockito.mock(S3Client.class);
        FileStore store = new S3FileStore(s3, "files");

        store.delete("org/photos/x");
        store.delete(null);

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, times(1)).deleteObject(req.capture());
        assertThat(req.getValue().key()).isEqualTo("org/photos/x");
        verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("S3 mode without a bucket name fails at startup with a clear message")
    void s3ModeNeedsBucket() {
        assertThatThrownBy(() -> new StorageConfig().s3FileStore(" ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("STORAGE_BUCKET");
    }

    @Test
    @DisplayName("the database store is the default")
    void defaultIsDatabase() {
        assertThat(new StorageConfig().databaseFileStore()).isInstanceOf(DatabaseFileStore.class);
    }
}
