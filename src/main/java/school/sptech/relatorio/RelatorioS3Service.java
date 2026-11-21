package school.sptech.relatorio;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// Envia o pdf pro s3, um arquivo por dia que é atualizado de hora em hora

public class RelatorioS3Service {

    private static final String PREFIXO = "client/relatorios/";

    private final S3Client s3;
    private final String bucket;

    public RelatorioS3Service(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String enviar(byte[] pdf) {
        String chave = PREFIXO + "relatorio-" + DataRelatorio.formatarParaChave(DataRelatorio.hojeNoCenario()) + ".pdf";

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(chave)
                .contentType("application/pdf")
                .build();

        s3.putObject(request, RequestBody.fromBytes(pdf));

        return chave;
    }
}
