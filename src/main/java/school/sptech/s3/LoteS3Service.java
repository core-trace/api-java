package school.sptech.s3;

import school.sptech.config.Env;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static school.sptech.Main.chaveMarcador;

public class LoteS3Service {

    private final S3Client s3;
    private final String bucket;

    public LoteS3Service(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * Para compatibilidade, S3_KEY seleciona um único arquivo se estiver preenchido.
     * Sem S3_KEY, o sistema descobre todos os arquivos .csv dentro de S3_PREFIX.
     */
    public List<String> obterChavesCaptura() {
        String chaveUnica = Env.get("S3_KEY", "").trim();
        if (!chaveUnica.isEmpty()) {
            return List.of(chaveUnica);
        }

        String prefixo = Env.get("S3_PREFIX", "client/lotes").trim();
        if (!prefixo.endsWith("/")) {
            prefixo += "/";
        }

        List<String> chaves = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefixo)
                .build();

        for (var pagina : s3.listObjectsV2Paginator(request)) {
            for (S3Object objeto : pagina.contents()) {
                String chave = objeto.key();

                // Busca apenas arquivos de captura.
                // Outros arquivos JSON não entram no processamento.
                if (chave != null && chave.endsWith("/capturas.json")) {
                    chaves.add(chave);
                }
            }
        }

        chaves.sort(Comparator.naturalOrder());
        return chaves;
    }

    /**
     * Verifica se o JSON já foi processado anteriormente.
     *
     * Utiliza headObject(), que consulta o objeto no S3
     * sem precisar baixar seu conteúdo.
     *
     * Retorna:
     * true  -> marcador encontrado
     * false -> marcador não existe
     *
     * Erros de permissão ou de serviço são propagados.
     */
    public boolean loteJaProcessado(String chave) {
        // Descobre o caminho do .ok correspondente.
        String marcador = chaveMarcador(chave);

        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(marcador)
                .build();

        try {

            // Se a chamada funcionar, o objeto existe.
            s3.headObject(request);

            return true;

        } catch (S3Exception e) {

            // 404 indica que o marcador não foi encontrado.
            if (e.statusCode() == 404)
                return false;

            // Não devemos tratar erros 403, 500 etc.
            // como se o arquivo ainda não existisse.
            throw e;
        }
    }

    /**
     * Cria o arquivo .ok no S3, registrando que
     * determinada captura foi processada com sucesso.
     *
     * Deve ser executado somente depois de todas
     * as etapas obrigatórias do processamento.
     */
    public void marcarLoteProcessado(String chave) {

        String marcador = chaveMarcador(chave);

        // Informações simples para auditoria.
        String conteudo = "STATUS=PROCESSADO\n"
                + "ORIGEM=" + chave + "\n"
                + "DATA=" + Instant.now();

        // Requisição para criar o marcador no bucket.
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(this.bucket)
                .key(marcador)
                .contentType("text/plain")
                .build();

        // Envia o conteúdo como texto para o S3.
        // Se a operação falhar, lança exceção.
        s3.putObject(
                request,
                RequestBody.fromString(conteudo)
        );

        System.out.println(
                "Marcador criado com sucesso: " + marcador
        );
    }
    // chaveMarcador() fica privado nesta classe.
}
