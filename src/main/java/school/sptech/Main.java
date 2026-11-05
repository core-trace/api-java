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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.time.Instant;

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

    /**
     * Representa uma ocorrência identificada durante
     * a análise das métricas.
     *
     * Ainda não é um chamado do Jira.
     * É apenas o resultado de uma comparação com o banco.
     */
    private record Ocorrencia(
            String mac,
            String recurso,
            String metrica,
            String campoOrigem,
            double valor,
            String nivel,
            String operador,
            double limite
    ) {}

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

    /**
     * Registra uma ocorrência no lote.
     *
     * Caso já exista uma ocorrência para a mesma
     * máquina, recurso e métrica, mantém a maior gravidade.
     *
     * CRITICO > ATENCAO.
     */
    private static void registrarOcorrencia(
            Map<String, Ocorrencia> ocorrencias,
            Ocorrencia nova
    ) {

        // Monta uma identificação para a ocorrência.
        // Incluímos campoOrigem para distinguir leituras
        // diferentes que compartilham o mesmo nome de métrica.
        String chave = String.join("|",
                nova.mac(),
                nova.recurso(),
                nova.metrica(),
                nova.campoOrigem() == null ? "" : nova.campoOrigem()
        );

        Ocorrencia anterior = ocorrencias.get(chave);

        // Primeira ocorrência desse recurso e métrica.
        if (anterior == null) {
            ocorrencias.put(chave, nova);
            return;
        }

        // Crítico sempre prevalece sobre atenção.
        if (nova.nivel().equals("CRITICO")
                && anterior.nivel().equals("ATENCAO")) {

            ocorrencias.put(chave, nova);
            return;
        }

        // Se ambas possuem a mesma gravidade,
        // mantém a última ocorrência processada.
        if (nova.nivel().equals(anterior.nivel())) {
            ocorrencias.put(chave, nova);
        }
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

                        // 1. Verifica se já existe um .ok no S3.
                        // Se existir, o arquivo não precisa ser lido novamente.
                        if (loteJaProcessado(s3Client, bucketName, chave)) {

                            System.out.println(
                                    "[IGNORADO] Arquivo já processado: " + chave
                            );

                            continue;
                        }

                        // 2. Se não existe .ok, processa o JSON.
                        // Isso inclui a leitura das métricas,
                        // consulta ao MySQL e comparação com os limites.
                        int leituras = processarJson(
                                s3Client,
                                bucketName,
                                chave,
                                dao,
                                jira,
                                jaAlertados
                        );

                        // 3. O método terminou sem lançar exceção.
                        // Atualiza as estatísticas da execução.
                        totalLinhas += leituras;
                        arquivosProcessados++;

                        // 4. Registra no S3 que o JSON foi processado.
                        // Por enquanto, usamos o prefixo de testes.
                        marcarLoteProcessado(
                                s3Client,
                                bucketName,
                                chave
                        );

                        System.out.println(
                                "[SUCESSO] Arquivo processado: " + chave
                        );

                    } catch (S3Exception e) {

                        // Problemas no S3 não geram marcador.
                        // O arquivo poderá ser reprocessado.
                        System.err.println(
                                "[ERRO S3] " + chave + ": " + e.getMessage()
                        );

                    } catch (IOException e) {

                        // Falhas na leitura ou comparação também
                        // impedem a criação do marcador.
                        System.err.println(
                                "[ERRO JSON] " + chave + ": " + e.getMessage()
                        );

                        e.printStackTrace();
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
                        totalLeituras += processarRegistros(
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
            criarTicket(jira, alerta);
        }

        return totalLeituras;
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
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        return percorrerArray(
                parser,
                tipo,
                null,
                mac,
                dao,
                cacheLimites,
                metricasConsultadas,
                alertasDoLote
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
            String recurso,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
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
                // Cada elemento herda o identificador
                // do recurso ao qual o array pertence
                total += percorrerObjeto(
                        parser,
                        caminhoItem,
                        recurso,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
                );

            } else if (token == JsonToken.START_ARRAY) {

                // Encontramos outro array dentro do array atual.
                // O método chama a si mesmo: recursividade.
                // Repassa o identificador ao array interno
                total += percorrerArray(
                        parser,
                        caminhoItem,
                        recurso,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
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
            String recurso,
            String mac,
            LimiteDao dao,
            Map<String, LimiteDao.Limite> cacheLimites,
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
    ) throws IOException {

        // Informações que caracterizam uma leitura de métrica.
        // Cada chamada recursiva possui suas próprias variáveis.
        String metrica = null;
        String campoOrigem = null;
        String status = null;
        Double valor = null;

        // Informações utilizadas para identificar
        // CPUs lógicas, discos, redes e processos.
        String indice = null;
        String nome = null;
        String dispositivo = null;
        String montagem = null;
        String pid = null;

        int total = 0;
        JsonToken token;

        // Percorre os campos até encontrar o fechamento: }
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {

            if (token == null)
                throw new IOException("Objeto JSON inválido");

            // Nome do campo que estamos analisando.
            String campo = parser.currentName();

            // Avança para o valor desse campo.
            token = parser.nextToken();

            if (token == null)
                throw new IOException("Objeto JSON incompleto");

            // Antes de entrar em um objeto interno,
            // verifica se já identificamos o recurso pai.
            String recursoAtual = identificarRecurso(
                    caminho, recurso, indice, nome,
                    dispositivo, montagem, pid
            );

            if (token == JsonToken.START_OBJECT) {

                // Encontrou um objeto dentro do objeto atual.
                // Faz outra chamada ao mesmo método.
                total += percorrerObjeto(
                        parser,
                        caminho + "." + campo,
                        recursoAtual,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
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
                        recursoAtual,
                        mac,
                        dao,
                        cacheLimites,
                        metricasConsultadas,
                        alertasDoLote
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

                // Identificadores de recursos.
                case "indice" ->
                        indice = parser.getValueAsString();

                case "nome" ->
                        nome = parser.getValueAsString();

                case "dispositivo" ->
                        dispositivo = parser.getValueAsString();

                case "montagem" ->
                        montagem = parser.getValueAsString();

                case "pid" ->
                        pid = parser.getValueAsString();
            }
        }

        // A avaliação só acontece depois que terminamos
        // de percorrer os campos do objeto atual.
        if ("ok".equals(status)
                && metrica != null
                && !metrica.isBlank()
                && valor != null
                && Double.isFinite(valor)) {

            // Depois de ler o objeto completo, verifica
            // se ele possui um identificador mais específico.
            // Isso é importante para CPUs lógicas, porque
            // o campo "indice" pode aparecer após "valor".
            String recursoIdentificado = identificarRecurso(
                    caminho, recurso, indice, nome,
                    dispositivo, montagem, pid
            );

            // A leitura passou nas validações.
            // Agora podemos consultar e comparar seus limites.
            avaliarMetrica(
                    mac,
                    metrica,
                    campoOrigem,
                    valor,
                    recursoIdentificado,
                    dao,
                    cacheLimites,
                    metricasConsultadas,
                    alertasDoLote
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
            Set<String> metricasConsultadas,
            Map<String, Ocorrencia> alertasDoLote
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
        if (limite == null) {
            System.out.printf(
                    "[SEM LIMITE] MAC: %s | Métrica: %s%n",
                    mac, metrica);

            return;
        }

        // Mostra que o Java encontrou a configuração da métrica
        // no MySQL e está prestes a realizar a comparação.
        System.out.printf(
                "[COMPARANDO] Recurso: %s | Métrica: %s | " +
                        "Valor: %.2f | Operador 1: %s | Operador 2: %s%n",
                recurso, metrica, valor,
                limite.operador(), limite.operador2()
        );

        // Guarda a gravidade e a condição que gerou o alerta.
        String nivel;
        Double limiteAtingido;
        String operadorAplicado;

        // Verifica as condições críticas dos dois operadores.
        // O segundo só é avaliado se houver operador configurado.
        boolean critico1 = atingiuLimite(
                valor, limite.critico(), limite.operador()
        );

        boolean critico2 = limite.operador2() != null
                && !limite.operador2().isBlank()
                && atingiuLimite(
                valor, limite.critico2(), limite.operador2()
        );

        // Faz o mesmo para as condições de atenção.
        boolean atencao1 = atingiuLimite(
                valor, limite.atencao(), limite.operador()
        );

        boolean atencao2 = limite.operador2() != null
                && !limite.operador2().isBlank()
                && atingiuLimite(
                valor, limite.atencao2(), limite.operador2()
        );

        // Prioridade 1: qualquer condição crítica.
        // Isso impede que uma atenção prevaleça sobre um crítico.
        if (critico1 || critico2) {

            nivel = "CRITICO";

            // Identifica qual condição crítica foi satisfeita.
            if (critico1) {
                limiteAtingido = limite.critico();
                operadorAplicado = limite.operador();
            } else {
                limiteAtingido = limite.critico2();
                operadorAplicado = limite.operador2();
            }

        // Prioridade 2: atenção, somente quando não houve crítico.
        } else if (atencao1 || atencao2) {

            nivel = "ATENCAO";

            if (atencao1) {
                limiteAtingido = limite.atencao();
                operadorAplicado = limite.operador();
            } else {
                limiteAtingido = limite.atencao2();
                operadorAplicado = limite.operador2();
            }

        } else return;

        // Cria uma ocorrência com o resultado da comparação.
        Ocorrencia ocorrencia = new Ocorrencia(
                mac,
                recurso,
                metrica,
                campoOrigem,
                valor,
                nivel,
                operadorAplicado,
                limiteAtingido
        );

        // Encaminha para o agrupamento.
        // Caso já exista uma ocorrência equivalente,
        // será aplicada a prioridade de gravidade.
        registrarOcorrencia(alertasDoLote, ocorrencia);
    }

    /**
     * Identifica o recurso físico ou lógico associado
     * a determinado objeto do JSON.
     *
     * O identificador herdado vem do objeto pai.
     * Exemplo: a métrica de ocupação herda "/dev/sda1"
     * do objeto que representa o volume.
     */
    private static String identificarRecurso(
            String caminho,
            String herdado,
            String indice,
            String nome,
            String dispositivo,
            String montagem,
            String pid
    ) {

        // Cada item de metricas representa uma captura
        // da mesma máquina em um instante diferente.
        //
        // Não usamos metricas[0] ou metricas[1] como
        // identificação do recurso, pois isso impediria
        // agrupar as leituras da CPU total e outras métricas.
        if (caminho.matches("^metricas\\[\\d+\\]$")) {
            return "Máquina";
        }

        // CPU lógica: identificada pelo índice.
        if (caminho.contains(".logicas.leituras[")
                && indice != null) {
            return "CPU lógica " + indice;
        }

        // Volume de disco: identificamos o dispositivo
        // e também o ponto de montagem, se disponível.
        if (caminho.contains(".disco.volumes[")
                && dispositivo != null) {
            return "Volume " + dispositivo
                    + (montagem != null ? " (" + montagem + ")" : "");
        }

        // Disco utilizado nas capturas de I/O.
        if (caminho.contains(".disco.io[")
                && dispositivo != null) {
            return "Disco " + dispositivo;
        }

        // Interface de rede.
        if (caminho.contains(".rede.interfaces[")
                && nome != null) {
            return "Interface " + nome;
        }

        // Processo individual.
        if (caminho.startsWith("processos[")
                && !caminho.contains(".")
                && nome != null) {
            return "Processo " + nome
                    + (pid != null ? " (PID " + pid + ")" : "");
        }

        // Contêiner individual.
        if (caminho.startsWith("containers[")
                && !caminho.contains(".")
                && nome != null) {
            return "Contêiner " + nome;
        }

        // Se o objeto não possui identificador próprio,
        // mantém o identificador recebido do pai.
        return herdado != null ? herdado : caminho;
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

    /**
     * Monta a chave S3 do arquivo .ok correspondente
     * a um arquivo de captura.
     *
     * Exemplo:
     * client/lotes/maquina=01/lote=abc/capturas.json
     *
     * Resultado:
     * client/controle/processados/maquina=01/lote=abc/capturas.json.ok
     */
    private static String chaveMarcador(String chave) {
        // Prefixo onde o Python envia os arquivos JSON.
        String prefixoCapturas = Env.get(
                "S3_PREFIX", "client/lotes/"
        ).trim();

        // Prefixo onde o Java registra os processamentos.
        String prefixoProcessados = Env.get(
                "S3_PROCESSADOS_PREFIX",
                "client/controle/testes-processados/"
        ).trim();

        // Padroniza os prefixos para evitar erros
        // ao concatenar os caminhos.
        if (!prefixoCapturas.endsWith("/"))
            prefixoCapturas += "/";

        if (!prefixoProcessados.endsWith("/"))
            prefixoProcessados += "/";

        // Impede gerar marcadores para arquivos que
        // não pertençam ao diretório esperado.
        if (!chave.startsWith(prefixoCapturas)
                || !chave.endsWith("/capturas.json")) {

            throw new IllegalArgumentException(
                    "Chave de captura inválida: " + chave
            );
        }

        // Remove o prefixo original e mantém
        // maquina, data, lote e nome do arquivo.
        String caminhoRelativo =
                chave.substring(prefixoCapturas.length());

        return prefixoProcessados + caminhoRelativo + ".ok";
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
    private static boolean loteJaProcessado(
            S3Client s3,
            String bucket,
            String chave
    ) {

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
    private static void marcarLoteProcessado(
            S3Client s3,
            String bucket,
            String chave
    ) {

        String marcador = chaveMarcador(chave);

        // Informações simples para auditoria.
        String conteudo = "STATUS=PROCESSADO\n"
                + "ORIGEM=" + chave + "\n"
                + "DATA=" + Instant.now();

        // Requisição para criar o marcador no bucket.
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
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

    /**
     * Verifica se existe um chamado para o mesmo problema.
     * Se não existir, cria um novo no Jira.
     *
     * Os logs ajudam a verificar se a etiqueta e o título
     * estão sendo gerados corretamente.
     */
    private static void criarTicket(
            Jira jira,
            Ocorrencia alerta
    ) throws IOException {

        String projeto = Env.require("JIRA_PROJECT_KEY");

        // A etiqueta identifica o problema entre diferentes lotes.
        String etiqueta = gerarEtiqueta(alerta);

        // O título apresenta a ocorrência de forma amigável.
        String resumo = montarTitulo(alerta);

        if (resumo.length() > 255) {
            resumo = resumo.substring(0, 255);
        }

        // LOGS TEMPORÁRIOS DE DIAGNÓSTICO
        System.out.println("[DEBUG] Projeto: " + projeto);
        System.out.println("[DEBUG] Etiqueta: " + etiqueta);
        System.out.println("[DEBUG] Título gerado: " + resumo);

        // Procura um chamado ainda não concluído.
        String chamadoExistente = jira.buscarChamadoAberto(
                projeto, etiqueta
        );

        System.out.println(
                "[DEBUG] Resultado da busca: " + chamadoExistente
        );

        if (chamadoExistente != null) {

            // Se a nova ocorrência for crítica, verifica
            // se o chamado existente precisa ser escalado.
            if ("CRITICO".equals(alerta.nivel())) {

                // Monta o título com o novo valor crítico.
                String tituloCritico = montarTitulo(alerta);

                if (tituloCritico.length() > 255) {
                    tituloCritico = tituloCritico.substring(0, 255);
                }

                // Consulta o chamado e atualiza apenas
                // se ainda não estiver classificado como crítico.
                boolean atualizado = jira.atualizarChamadoParaCritico(
                        chamadoExistente,
                        tituloCritico
                );

                if (atualizado) {
                    System.out.printf(
                            "[JIRA] Chamado %s atualizado para CRITICO.%n",
                            chamadoExistente
                    );
                } else {
                    System.out.printf(
                            "[JIRA] Chamado %s já está CRITICO.%n",
                            chamadoExistente
                    );
                }

            } else {

                // Uma nova ocorrência de atenção não deve
                // rebaixar um chamado que já esteja crítico.
                System.out.printf(
                        "[JIRA] Chamado %s já existe. Mantendo gravidade.%n",
                        chamadoExistente
                );
            }

            // Não cria um chamado novo.
            return;
        }

        try {
            // Cria o novo chamado com título e etiqueta.
            String resposta = jira.createIssue(
                    projeto,
                    resumo,
                    Env.get("JIRA_ISSUE_TYPE", "Task"),
                    etiqueta
            );

            System.out.println("[JIRA] Chamado criado: " + resposta);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException(
                    "Criação do chamado interrompida", e
            );
        }
    }

    /**
     * Monta um título amigável para os chamados do Jira.
     *
     * Transforma identificadores técnicos em descrições
     * compreensíveis para o operador de infraestrutura.
     */
    private static String montarTitulo(Ocorrencia alerta) {

        // Tradução do nível de gravidade.
        String nivel = alerta.nivel().equals("CRITICO")
                ? "CRÍTICO"
                : "ATENÇÃO";

        // Descrição da métrica para o título.
        String descricao = switch (alerta.metrica()) {
            case "cpu_percent", "cpu_percent_percpu" ->
                    "Uso elevado de " + alerta.recurso();

            case "ram_percent" ->
                    "Uso de memória em " + alerta.recurso();

            default ->
                    alerta.metrica() + " em " + alerta.recurso();
        };

        // Os nomes acima são descrições genéricas.
        // Para operadores como <, usamos uma expressão
        // neutra, porque pode ser um valor muito baixo.
        if (alerta.operador().startsWith("<")) {
            descricao = "Valor baixo de " + alerta.metrica()
                    + " em " + alerta.recurso();
        }

        String unidade = switch (alerta.metrica()) {
            case "cpu_percent", "cpu_percent_percpu",
                 "ram_percent", "disk_percent" -> "%";
            default -> "";
        };

        // O Locale brasileiro utiliza vírgula decimal.
        return String.format(
                Locale.forLanguageTag("pt-BR"),
                "[%s] %s — %.1f%s (limite %s %.1f%s) | Servidor %s",
                nivel,
                descricao,
                alerta.valor(),
                unidade,
                alerta.operador(),
                alerta.limite(),
                unidade,
                alerta.mac()
        );
    }

    /**
     * Gera uma etiqueta estável para identificar
     * o mesmo problema em diferentes lotes.
     *
     * A etiqueta não depende do valor medido,
     * da gravidade ou do lote.
     */
    private static String gerarEtiqueta(Ocorrencia alerta) {

        String identificador = String.join("|",
                alerta.mac(),
                alerta.recurso(),
                alerta.metrica(),
                alerta.campoOrigem() == null
                        ? ""
                        : alerta.campoOrigem()
        ).toLowerCase(Locale.ROOT);

        try {
            // Converte a identificação em um hash estável.
            byte[] hash = java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(identificador.getBytes(
                            StandardCharsets.UTF_8
                    ));

            // Utilizamos os primeiros 24 caracteres do hash
            // para manter uma etiqueta curta.
            return "coretrace-"
                    + java.util.HexFormat.of()
                    .formatHex(hash)
                    .substring(0, 24);

        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 indisponível", e
            );
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
