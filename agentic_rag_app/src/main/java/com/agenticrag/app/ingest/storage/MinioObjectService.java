package com.agenticrag.app.ingest.storage;

import com.agenticrag.app.ingest.config.MinioStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ingest.file-storage.backend", havingValue = "minio")
public class MinioObjectService {
	private final MinioClient minioClient;
	private final MinioStorageProperties properties;

	public MinioObjectService(MinioClient minioClient, MinioStorageProperties properties) {
		this.minioClient = minioClient;
		this.properties = properties;
	}

	public String getDefaultBucket() {
		return properties.getBucket();
	}

	public void putBytes(String bucket, String objectKey, byte[] bytes, String contentType) {
		byte[] safe = bytes != null ? bytes : new byte[0];
		ensureBucket(bucket);
		try {
			minioClient.putObject(
				PutObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.stream(new ByteArrayInputStream(safe), safe.length, -1)
					.contentType(contentType != null ? contentType : "application/octet-stream")
					.build()
			);
		} catch (Exception e) {
			throw new IllegalStateException("failed to upload object to minio", e);
		}
	}

	public String getPresignedGetUrl(String bucket, String objectKey) {
		try {
			int expiry = Math.max(60, properties.getPresignExpirySeconds());
			return minioClient.getPresignedObjectUrl(
				GetPresignedObjectUrlArgs.builder()
					.method(Method.GET)
					.bucket(bucket)
					.object(objectKey)
					.expiry(expiry, TimeUnit.SECONDS)
					.build()
			);
		} catch (Exception e) {
			throw new IllegalStateException("failed to presign object url", e);
		}
	}

	public StoredObject getObject(String bucket, String objectKey) {
		try {
			String contentType = minioClient.statObject(
				StatObjectArgs.builder().bucket(bucket).object(objectKey).build()
			).contentType();
			InputStream stream = minioClient.getObject(
				GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
			);
			byte[] bytes = stream.readAllBytes();
			stream.close();
			return new StoredObject(bytes, contentType != null ? contentType : "application/octet-stream");
		} catch (ErrorResponseException e) {
			throw new IllegalArgumentException("object not found");
		} catch (Exception e) {
			throw new IllegalStateException("failed to read object from minio", e);
		}
	}

	public void removeObject(String bucket, String objectKey) {
		try {
			minioClient.removeObject(
				RemoveObjectArgs.builder()
					.bucket(bucket)
					.object(objectKey)
					.build()
			);
		} catch (Exception ignored) {
		}
	}

	private void ensureBucket(String bucket) {
		try {
			boolean exists = minioClient.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build());
			if (exists) {
				return;
			}
			minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
		} catch (Exception e) {
			throw new IllegalStateException("failed to ensure minio bucket", e);
		}
	}

	public static class StoredObject {
		private final byte[] bytes;
		private final String contentType;

		public StoredObject(byte[] bytes, String contentType) {
			this.bytes = bytes != null ? bytes : new byte[0];
			this.contentType = contentType;
		}

		public byte[] getBytes() {
			return bytes;
		}

		public String getContentType() {
			return contentType;
		}
	}
}
