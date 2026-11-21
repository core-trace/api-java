package school.sptech.relatorio;

import school.sptech.client.S3Provider;
import school.sptech.config.Env;
import school.sptech.dto.ResumoGeralDto;
import software.amazon.awssdk.services.s3.S3Client;

// Pega o resumo, usa o montador do html, usa conversor pra dof e usa o obj de subir para o S3. Isso é chamado pelo agendador

public class RelatorioService {

    public void executar() throws Exception {
        ResumoGeralDto resumo = new ResumoGeralClient().buscarResumo();

        String html = new GeradorHtmlRelatorio().gerarHtmlRelatorioGeral(resumo);

        byte[] pdf = new ConversorPdf().converterParaPdf(html);

        String bucketName = Env.require("S3_BUCKET");
        try (S3Client s3Client = new S3Provider().getS3Client()) {
            String chave = new RelatorioS3Service(s3Client, bucketName).enviar(pdf);
            System.out.println("Relatório geral atualizado: s3://" + bucketName + "/" + chave);
        }
    }
}
