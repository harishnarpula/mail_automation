package com.aiautomationservice.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.HttpMethod;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class S3Service {

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.access-key:}")
    private String accessKeyId;

    @Value("${aws.s3.secret-key:}")
    private String secretKey;

    private AmazonS3 s3Client;

    @PostConstruct
    public void init() {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
                .withRegion(Regions.AP_SOUTH_1);

        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretKey)) {
            BasicAWSCredentials credentials = new BasicAWSCredentials(accessKeyId, secretKey);
            builder = builder.withCredentials(new AWSStaticCredentialsProvider(credentials));
            log.info("S3 initialized with static credentials bucket={} region=AP_SOUTH_1", bucket);
        } else {
            builder = builder.withCredentials(DefaultAWSCredentialsProviderChain.getInstance());
            log.warn("AWS access key/secret not set; using default AWS credentials provider chain.");
        }

        s3Client = builder.build();

        log.info("S3 client ready bucket={} region=AP_SOUTH_1", bucket);
    }

    public String uploadFile(MultipartFile file, String s3Key) throws Exception {
        byte[] bytes = file.getBytes();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(file.getContentType());

        ByteArrayInputStream input = new ByteArrayInputStream(bytes);

        PutObjectRequest request = new PutObjectRequest(bucket, s3Key, input, metadata);

        s3Client.putObject(request);

        log.info("Uploaded {}", s3Key);

        return s3Key;
    }

    public String generatePresignedUrl(String s3Key) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 7);

        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, s3Key)
                .withMethod(HttpMethod.GET)
                .withExpiration(cal.getTime());

        URL url = s3Client.generatePresignedUrl(request);

        return url.toString();    }

    public String resolveUrl(String s3KeyOrUrl) {
        if (s3KeyOrUrl == null || s3KeyOrUrl.isBlank())
            return null;

        if (s3KeyOrUrl.startsWith("http"))
            return s3KeyOrUrl;

        return generatePresignedUrl(s3KeyOrUrl);
    }
    public String uploadBytes(
            byte[] bytes,
            String s3Key,
            String contentType) {

        ObjectMetadata metadata =
                new ObjectMetadata();

        metadata.setContentLength(
                bytes.length);

        metadata.setContentType(
                contentType);

        ByteArrayInputStream input =
                new ByteArrayInputStream(
                        bytes);

        PutObjectRequest request =
                new PutObjectRequest(
                        bucket,
                        s3Key,
                        input,
                        metadata);

        s3Client.putObject(request);

        log.info(
                "Uploaded image {}",
                s3Key);

        return s3Key;
    }

    public byte[] downloadBytes(String s3Key) {
        try {
            com.amazonaws.services.s3.model.S3Object object = s3Client.getObject(bucket, s3Key);
            try (java.io.InputStream is = object.getObjectContent()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to download from S3: " + s3Key, e);
        }
    }
}