package school.sptech;

import school.sptech.client.S3Provider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        S3Client s3Client = new S3Provider().getS3Client();

        String bucketName = "testejavacoretrace";
        String key = "03-gold/server_metrics_gold.csv";

        try {
            List<Map<String, String>> registros = lerCsv(s3Client, bucketName, key);
            System.out.println("Linhas lidas: " + registros.size());

            for (Map<String, String> registro : registros) {
                // Aqui entra a validação com limite_alerta / limite_critico (depois)
                System.out.println(registro);
            }

        } catch (S3Exception e) {
            System.err.println("Erro ao acessar o S3: " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            System.err.println("Erro ao ler o CSV: " + e.getMessage());
        }
    }

    /**
     * Lê o CSV direto do S3 (sem salvar em disco).
     * Cada linha vira um Map: nome da coluna -> valor.
     */
    private static List<Map<String, String>> lerCsv(S3Client s3, String bucket, String key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        List<Map<String, String>> registros = new ArrayList<>();

        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(request);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String cabecalho = reader.readLine();
            if (cabecalho == null) {
                return registros; // arquivo vazio
            }

            // remove BOM (comum em CSV gerado por Excel/pandas) e separa as colunas
            String[] colunas = cabecalho.replace("\uFEFF", "").split(",", -1);

            String linha;
            while ((linha = reader.readLine()) != null) {
                if (linha.isBlank()) continue;

                String[] valores = linha.split(",", -1);
                Map<String, String> registro = new LinkedHashMap<>();
                for (int i = 0; i < colunas.length; i++) {
                    String valor = i < valores.length ? valores[i].trim() : "";
                    registro.put(colunas[i].trim(), valor);
                }
                registros.add(registro);
            }
        }

        return registros;
    }
}