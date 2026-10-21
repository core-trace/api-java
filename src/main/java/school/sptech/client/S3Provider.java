package school.sptech.client;

import school.sptech.config.Env;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class S3Provider {

    private final AwsCredentialsProvider credentials;

    public S3Provider() {
        // Em EC2, recomenda-se usar uma IAM Role em vez de chaves no .env.
        this.credentials = DefaultCredentialsProvider.create();
    }

    public S3Client getS3Client() {
        String regiao = Env.get("AWS_REGION", "us-east-1");
        return S3Client.builder()
                .region(Region.of(regiao))
                .credentialsProvider(credentials)
                .build();
    }
}
