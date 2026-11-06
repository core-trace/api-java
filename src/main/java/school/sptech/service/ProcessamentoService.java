package school.sptech.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import school.sptech.Main.Ocorrencia;
import school.sptech.client.MySqlProvider;
import school.sptech.client.S3Provider;
import school.sptech.config.Env;
import school.sptech.config.Jira;
import school.sptech.docker.LimiteDao;
import school.sptech.s3.LoteS3Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ProcessamentoService {

    public void executarProcessamento() {

        try {
            validarConfiguracao();

            String bucketName = Env.require("S3_BUCKET");

            LimiteDao dao = new LimiteDao(new MySqlProvider());
            Jira jira = criarJira();

            Set<String> jaAlertados = new HashSet<>();

            try (S3Client s3Client = new S3Provider().getS3Client()) {

                // Instancia o serviço responsável pelos lotes do S3.
                LoteS3Service loteService = new LoteS3Service(
                        s3Client,
                        bucketName
                );

                // Agora a listagem é responsabilidade do serviço.
                List<String> chavesJson =
                        loteService.obterChavesCaptura();

                if (chavesJson.isEmpty()) {
                    System.out.println(
                            "Nenhum JSON encontrado no S3."
                    );
                    return;
                }

                System.out.printf(
                        "Objetos JSON encontrados: %d%n",
                        chavesJson.size()
                );

                int totalLinhas = 0;
                int arquivosProcessados = 0;

                for (String chave : chavesJson) {

                    try {

                        // Verifica se o lote já foi processado.
                        if (loteService.loteJaProcessado(chave)) {

                            System.out.println(
                                    "[IGNORADO] Arquivo já processado: "
                                            + chave
                            );

                            continue;
                        }

                        // Continua utilizando o processarJson()
                        // que ainda está dentro da Main.
                        int leituras = processarJson(
                                s3Client,
                                bucketName,
                                chave,
                                dao,
                                jira,
                                jaAlertados
                        );

                        // Só marca depois do processamento completo.
                        loteService.marcarLoteProcessado(chave);

                        totalLinhas += leituras;
                        arquivosProcessados++;

                        System.out.println(
                                "[SUCESSO] Arquivo processado: " + chave
                        );

                    } catch (S3Exception e) {

                        System.err.println(
                                "[ERRO S3] " + chave + ": "
                                        + e.getMessage()
                        );

                    } catch (IOException e) {

                        System.err.println(
                                "[ERRO PROCESSAMENTO] " + chave
                                        + ": " + e.getMessage()
                        );

                        e.printStackTrace();
                    }
                }

                System.out.printf(
                        "Processamento concluído. Arquivos: %d; Leituras: %d%n",
                        arquivosProcessados,
                        totalLinhas
                );
            }

        } catch (S3Exception e) {

            System.err.println(
                    "Erro ao acessar S3: " + e.getMessage()
            );

        } catch (IllegalStateException e) {

            System.err.println(
                    "Erro de configuração: " + e.getMessage()
            );
        }
    }

    private void validarConfiguracao() {
        Env.require("DB_HOST");
        Env.require("DB_NAME");
        Env.require("DB_USER");
        Env.require("DB_PASSWORD");
        Env.require("S3_BUCKET");
        Env.require("JIRA_BASE_URL");
        Env.require("JIRA_EMAIL");
        Env.require("JIRA_API_TOKEN");
        Env.require("JIRA_PROJECT_KEY");
        // S3_KEY é opcional. Se estiver preenchido, processamos apenas esse arquivo.
        // Caso contrário, listamos os CSVs que estiverem em S3_PREFIX.
    }



    private static Jira criarJira() {
        String url = Env.require("JIRA_BASE_URL").replaceAll("/+$", "");
        String email = Env.require("JIRA_EMAIL");
        String token = Env.require("JIRA_API_TOKEN");
        return new Jira(url, email, token);
    }

    /**
     * Processa um arquivo por vez. O cache de limites é local ao arquivo porque
     * arquivos diferentes podem ter conjuntos de colunas diferentes.
     */

    /**
     * Lê um arquivo JSON do S3 e encaminha seus registros
     * para o processamento.
     *
     * O arquivo pode conter métricas da máquina, processos
     * e contêineres.
     *
     * @return Quantidade de leituras válidas encontradas.
     */
    private static int processarJson(
            S3Client s3,
            String bucket,
            String chave,
            LimiteDao dao,
            Jira jira,
            Set<String> jaAlertados
    ) throws IOException {

        System.out.println("\nProcessando JSON: " + chave);

        // Cria a requisição para localizar o arquivo no S3.
        // A chave representa o caminho completo do objeto.
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(chave)
                .build();

        // Fábrica responsável por criar o leitor de JSON.
        JsonFactory factory = new JsonFactory();

        // Identificadores que serão extraídos do próprio arquivo.
        String mac = null;
        String loteId = null;

        // Contador de leituras válidas.
        int totalLeituras = 0;

        // Guarda os limites já encontrados no MySQL.
        // Chave: nome da métrica
        // Valor: configuração de limites correspondente.
        Map<String, LimiteDao.Limite> cacheLimites = new HashMap<>();

        // Registra quais métricas já foram consultadas.
        // Evita consultar novamente até mesmo métricas
        // que não possuem limites cadastrados.
        Set<String> metricasConsultadas = new HashSet<>();

        // Acumula as ocorrências deste arquivo.
        // LinkedHashMap mantém a ordem de inserção.
        Map<String, Ocorrencia> alertasDoLote = new LinkedHashMap<>();

        try (
                // Abre um fluxo de bytes diretamente do S3.
                InputStream inputStream = s3.getObject(request);

                // Interpreta o conteúdo do InputStream como JSON.
                JsonParser parser = factory.createParser(inputStream)
        ) {

            // O primeiro token deve representar a abertura
            // do objeto principal do JSON: {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IOException("JSON inválido");

            JsonToken token;

            // Percorre os campos do objeto principal até
            // encontrar o fechamento: }

            // O JsonParser transforma estrutura do Json em um sequência de tokens
            // A leitura dele ocorre dessa maneira onde cada campo seria o TOKEN:
//            START_OBJECT
//            {
//                FIELD_NAME
//                TIMESTAMP
//
//                VALUESTRING
//                2026-11-02T20:30:00
//
//                FILEDNAME
//                CPU_PERCENT
//
//                VALUE_NUMBER_FLOAT
//                95.5
//
//                END_OBJECT
//            }
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {

                // Esperamos encontrar sempre o nome de um campo.
                if (token != JsonToken.FIELD_NAME)
                    throw new IOException("Campo JSON inválido");

                // Obtém o nome do campo atual.
                String campo = parser.currentName();

                // Avança para o valor associado ao campo.
                token = parser.nextToken();

                if (token == null)
                    throw new IOException("JSON incompleto");

                switch (campo) {

                    // Identifica a máquina que enviou as capturas.
                    case "endereco_mac" ->
                            mac = parser.getValueAsString();

                    // Identifica o lote para posterior controle
                    // de processamento e prevenção de duplicações.
                    case "lote_id" ->
                            loteId = parser.getValueAsString();

                    // Os três tipos são arrays e seguem
                    // o mesmo fluxo de leitura.
                    case "metricas", "processos", "containers" -> {

                        // Não podemos consultar os limites do banco
                        // sem saber a qual máquina pertencem.
                        if (mac == null || mac.isBlank())
                            throw new IOException("MAC não identificado");

                        // Confirma que o campo contém um array: [...]
                        if (token != JsonToken.START_ARRAY)
                            throw new IOException("Esperado array: " + campo);

                        // Encaminha o parser, já posicionado no
                        // início do array, para processar os registros.
                        totalLeituras += ProcessadorJson.processarRegistros(
                                parser,
                                campo,
                                mac,
                                dao,
                                cacheLimites,
                                metricasConsultadas,
                                alertasDoLote
                        );
                    }

                    // Ignora campos que não fazem parte
                    // do processamento atual, inclusive seus filhos.
                    default -> parser.skipChildren();
                }
            }

        }
        // Neste ponto, o JSON inteiro já foi processado.
        // O mapa contém somente as ocorrências selecionadas
        // após a aplicação da prioridade de gravidade.

        if (chave != null && chave.toLowerCase(Locale.ROOT).endsWith(".json"))
            System.out.printf(
                    "MAC: %s | Lote: %s | Leituras válidas: %d%n",
                    mac, loteId, totalLeituras
            );

        System.out.println(
                "Ocorrências agrupadas: " + alertasDoLote.size()
        );

        for (Ocorrencia alerta : alertasDoLote.values()) {

            System.out.printf(
                    "[%s] %s | %s | %s = %.2f " +
                            "(%s %.2f)%n",
                    alerta.nivel(),
                    alerta.mac(),
                    alerta.recurso(),
                    alerta.metrica(),
                    alerta.valor(),
                    alerta.operador(),
                    alerta.limite()
            );

            // Cria um chamado no Jira para esta ocorrência.
            // Se houver erro, o método lança uma exceção,
            // impedindo a criação do marcador .ok.
            ChamadoService.criarTicket(jira, alerta);
        }

        return totalLeituras;
    }
}
