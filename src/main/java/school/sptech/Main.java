package school.sptech;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import school.sptech.client.MySqlProvider;
import school.sptech.client.S3Provider;
import school.sptech.config.Env;
import school.sptech.config.Jira;
import school.sptech.docker.LimiteDao;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CoreTrace: lê um ou mais CSVs do S3, procura os limites das métricas
 * configuradas para cada servidor no MySQL e cria ticket no Jira para alertas críticos.
 */
public class Main {

    private static final String COLUNA_MAC = "endereco_mac";
    private static final String COLUNA_TIMESTAMP = "timestamp";

    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                System.out.println("Verificando novos CSVs no S3...");

                executarProcessamento();

                System.out.println("\nProcessamento concluído em " + java.time.LocalDateTime.now() + ". Próxima verificação em 5 minutos.");
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.MINUTES);
    }

    private static void executarProcessamento() {
        try {
            validarConfiguracao();

            String bucketName = Env.require("S3_BUCKET");
            LimiteDao dao = new LimiteDao(new MySqlProvider());
            Jira jira = criarJira();
            Set<String> jaAlertados = new HashSet<>();

            try (S3Client s3Client = new S3Provider().getS3Client()) {
                List<String> chavesCsv = obterChavesCsv(s3Client, bucketName);

                if (chavesCsv.isEmpty()) {
                    System.out.printf(
                            "Nenhum objeto JSON encontrado no bucket '%s' com o prefixo configurado.%n",
                            bucketName);
                    return;
                }

                System.out.printf("Objetos JSON encontrados: %d%n", chavesCsv.size());
                int totalLinhas = 0;
                int arquivosProcessados = 0;

                for (String chave : chavesCsv) {
                    try {
                        totalLinhas += processarJson(
                                s3Client, bucketName, chave, dao, jira, jaAlertados);
                        arquivosProcessados++;
                    } catch (S3Exception e) {
                        String mensagem = e.awsErrorDetails() == null
                                ? e.getMessage()
                                : e.awsErrorDetails().errorMessage();
                        System.err.printf("Erro ao acessar o arquivo S3 '%s': %s%n", chave, mensagem);
                    } catch (IOException e) {
                        System.err.printf("Erro ao ler o JSON '%s': %s%n", chave, e.getMessage());
                    }
                }

                System.out.printf("Processamento concluído. Arquivos processados: %d; linhas lidas: %d.%n",
                        arquivosProcessados, totalLinhas);
            }
        } catch (S3Exception e) {
            String mensagem = e.awsErrorDetails() == null
                    ? e.getMessage()
                    : e.awsErrorDetails().errorMessage();
            System.err.println("Erro ao listar ou acessar o S3: " + mensagem);
        } catch (IllegalStateException e) {
            System.err.println("Erro de configuração: " + e.getMessage());
        }
    }

    private static void validarConfiguracao() {
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

    /**
     * Para compatibilidade, S3_KEY seleciona um único arquivo se estiver preenchido.
     * Sem S3_KEY, o sistema descobre todos os arquivos .csv dentro de S3_PREFIX.
     */
    private static List<String> obterChavesCsv(S3Client s3, String bucket) {
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

                if (chave != null && chave.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    chaves.add(chave);
                }
            }
        }

        chaves.sort(Comparator.naturalOrder());
        return chaves;
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
                        totalLeituras += processarRegistros(
                                parser,
                                campo,
                                mac,
                                dao,
                                cacheLimites,
                                metricasConsultadas
                        );
                    }

                    // Ignora campos que não fazem parte
                    // do processamento atual, inclusive seus filhos.
                    default -> parser.skipChildren();
                }
            }

            System.out.printf(
                    "MAC: %s | Lote: %s | Leituras válidas: %d%n",
                    mac, loteId, totalLeituras
            );

            return totalLeituras;
        }
    }
    /**
     * Encaminha os arrays principais do JSON para leitura.
     *
     * parâmetro tipo pode ser um Array de: metricas, processos ou containers.
     */
    private static int processarRegistros(
            JsonParser parser,
            String tipo,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas
    ) throws IOException {

        return percorrerArray(parser, tipo, mac, dao,
                cacheLimites, metricasConsultadas
        );
    }

    /**
     * Percorre os elementos de um array JSON.
     *
     * Cada elemento pode ser um objeto, outro array
     * ou um valor simples.
     *
     * O método utiliza recursividade para permitir
     * a leitura de arrays aninhados.
     */
    private static int percorrerArray(
            JsonParser parser,
            String caminho,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas
    ) throws IOException {

        // Posição atual dentro do array.
        int indice = 0;

        // Quantidade de leituras válidas encontradas.
        int total = 0;

        JsonToken token;

        // Percorre até encontrar o fechamento do array: ]
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {

            if (token == null)
                throw new IOException("Array JSON incompleto");

            // Monta o caminho do elemento.
            // Exemplo: metricas[0], metricas[1]...
            String caminhoItem = caminho + "[" + indice + "]";

            if (token == JsonToken.START_OBJECT) {

                // Encontramos um objeto dentro do array.
                // Encaminhamos sua leitura ao percorrerObjeto().
                total += percorrerObjeto(
                        parser,
                        caminhoItem,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas
                );

            } else if (token == JsonToken.START_ARRAY) {

                // Encontramos outro array dentro do array atual.
                // O método chama a si mesmo: recursividade.
                total += percorrerArray(
                        parser,
                        caminhoItem,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas
                );
            }

            // Avança o índice do elemento.
            indice++;
        }

        return total;
    }

    /**
     * Percorre os campos de um objeto JSON.
     *
     * Se encontrar objetos ou arrays internos, chama
     * os respectivos métodos recursivamente.
     *
     * Quando encontra uma leitura válida, encaminha
     * seus dados para avaliarMetrica().
     */
    private static int percorrerObjeto(
            JsonParser parser,
            String caminho,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas
    ) throws IOException {

        // Informações que caracterizam uma leitura de métrica.
        // Cada chamada recursiva possui suas próprias variáveis.
        String metrica = null;
        String campoOrigem = null;
        String status = null;
        Double valor = null;

        int total = 0;

        JsonToken token;

        // Percorre os campos até encontrar o fechamento: }
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {

            if (token == null)
                throw new IOException("Objeto JSON incompleto");

            // Nome do campo que estamos analisando.
            String campo = parser.currentName();

            // Avança para o valor desse campo.
            token = parser.nextToken();

            if (token == null)
                throw new IOException("Objeto JSON incompleto");

            if (token == JsonToken.START_OBJECT) {

                // Encontrou um objeto dentro do objeto atual.
                // Faz outra chamada ao mesmo método.
                total += percorrerObjeto(
                        parser,
                        caminho + "." + campo,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas
                );

                // O objeto interno já foi percorrido.
                // Continuamos com o próximo campo do objeto pai.
                continue;
            }

            if (token == JsonToken.START_ARRAY) {

                // Encontrou um array dentro do objeto.
                // Encaminha sua leitura para percorrerArray().
                total += percorrerArray(
                        parser,
                        caminho + "." + campo,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas
                );

                continue;
            }

            // Se não for objeto nem array, verifica
            // se é um dos campos necessários à leitura.
            switch (campo) {

                case "metrica" ->
                        metrica = parser.getValueAsString();

                case "campo_origem" ->
                        campoOrigem = parser.getValueAsString();

                case "status" ->
                        status = parser.getValueAsString();

                case "valor" -> {

                    // Apenas valores numéricos podem ser
                    // comparados com os limites atuais.
                    if (token.isNumeric())
                        valor = parser.getDoubleValue();
                }
            }
        }

        // A avaliação só acontece depois que terminamos
        // de percorrer os campos do objeto atual.
        if ("ok".equals(status)
                && metrica != null
                && !metrica.isBlank()
                && valor != null
                && Double.isFinite(valor)) {

            // A leitura passou nas validações.
            // Agora podemos consultar e comparar seus limites.
            avaliarMetrica(
                    mac,
                    metrica,
                    campoOrigem,
                    valor,
                    caminho,
                    dao,
                    cacheLimites,
                    metricasConsultadas
            );

            total++;
        }

        return total;
    }

    /**
     * Consulta os limites de uma métrica e verifica
     * se seu valor representa atenção ou crítico.
     *
     * A leitura já deve ter sido validada antes
     * de chegar a este método.
     */
    private static void avaliarMetrica(
            String mac,
            String metrica,
            String campoOrigem,
            double valor,
            String recurso,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas
    ) throws IOException {

        // Consulta os limites apenas uma vez para cada métrica
        // encontrada no arquivo.
        if (!metricasConsultadas.contains(metrica)) {
            try {
                Map<String, LimiteDao.Limite> encontrados =
                        dao.buscarPorMac(mac, List.of(metrica));

                // O DAO retorna as chaves em minúsculas.
                String chaveMetrica = metrica.toLowerCase(Locale.ROOT);

                LimiteDao.Limite limite = encontrados.get(chaveMetrica);

                // Armazena somente as métricas que possuem limites.
                if (limite != null) {
                    cacheLimites.put(metrica, limite);
                }

                // Registra que essa métrica já foi consultada,
                // mesmo quando não existe limite no banco.
                metricasConsultadas.add(metrica);

            } catch (SQLException e) {
                throw new IOException(
                        "Erro ao buscar limites para " + metrica, e
                );
            }
        }

        // Recupera a configuração já encontrada.
        LimiteDao.Limite limite = cacheLimites.get(metrica);

        // Métrica sem limite configurado não gera alerta.
        if (limite == null)
            return;

        String nivel;
        Double limiteAtingido;

        // Verifica primeiro o limite crítico.
        // Ele possui prioridade sobre o de atenção.
        if (atingiuLimite(valor, limite.critico(), limite.operador())) {

            nivel = "CRITICO";
            limiteAtingido = limite.critico();

        } else if (atingiuLimite(valor, limite.atencao(), limite.operador())) {

            nivel = "ATENCAO";
            limiteAtingido = limite.atencao();

        } else {

            // O valor não atingiu nenhum limite.
            return;
        }

        // Por enquanto, apenas exibe a ocorrência.
        // A integração com o Jira será adicionada depois.
        System.out.printf(
                "[%s] MAC: %s | Recurso: %s | Métrica: %s | " +
                        "Origem: %s | Valor: %.2f | Operador: %s | Limite: %.2f%n",
                nivel,
                mac,
                recurso,
                metrica,
                campoOrigem,
                valor,
                limite.operador(),
                limiteAtingido
        );
    }

    /** Avalia a métrica usando o operador configurado em componentes_perfil.operador. */
    private static boolean atingiuLimite(double valor, Double limite, String operador) {
        if (limite == null) {
            return false;
        }

        String op = (operador == null || operador.isBlank()) ? ">" : operador.trim();
        return switch (op) {
            case ">" -> valor > limite;
            case ">=" -> valor >= limite;
            case "<" -> valor < limite;
            case "<=" -> valor <= limite;
            case "=", "==" -> Double.compare(valor, limite) == 0;
            case "!=", "<>" -> Double.compare(valor, limite) != 0;
            default -> {
                System.err.printf("Operador '%s' não suportado; limite ignorado.%n", op);
                yield false;
            }
        };
    }

    private static void criarTicket(Jira jira, String resumo) {
        try {
            String resposta = jira.createIssue(
                    Env.require("JIRA_PROJECT_KEY"),
                    resumo,
                    Env.get("JIRA_ISSUE_TYPE", "Task"));
            System.out.println("  -> Ticket criado: " + resposta);
        } catch (IOException e) {
            System.err.println("  -> Erro de rede ao criar ticket: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("  -> Criação do ticket interrompida.");
        } catch (RuntimeException e) {
            System.err.println("  -> Jira recusou o ticket: " + e.getMessage());
        }
    }

    /**
     * Faz o parsing de uma linha CSV, respeitando campos entre aspas e vírgulas
     * dentro desses campos. O leitor considera uma linha física por registro.
     */
    private static List<String> parseCsvLine(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder campoAtual = new StringBuilder();
        boolean dentroDeAspas = false;

        for (int i = 0; i < linha.length(); i++) {
            char caractere = linha.charAt(i);

            if (caractere == '"') {
                if (dentroDeAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    campoAtual.append('"');
                    i++;
                } else {
                    dentroDeAspas = !dentroDeAspas;
                }
            } else if (caractere == ',' && !dentroDeAspas) {
                campos.add(campoAtual.toString());
                campoAtual.setLength(0);
            } else {
                campoAtual.append(caractere);
            }
        }

        campos.add(campoAtual.toString());
        return campos;
    }
}
