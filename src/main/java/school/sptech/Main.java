package school.sptech;

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
                        System.err.printf("Erro ao ler o CSV '%s': %s%n", chave, e.getMessage());
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
    private static int processarJson(
            S3Client s3,
            String bucket,
            String chave,
            LimiteDao dao,
            Jira jira,
            Set<String> jaAlertados) throws IOException {

        System.out.println("\nProcessando JSON: " + chave);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(chave)
                .build();

        System.out.println(request);

        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(request);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String linhaCabecalho = reader.readLine();
            if (linhaCabecalho == null) {
                System.err.println("Arquivo vazio; ignorando: " + chave);
                return 0;
            }

            // Normalizamos os nomes das colunas para minúsculas; os valores não são alterados.
            List<String> colunas = parseCsvLine(linhaCabecalho.replace("\uFEFF", "")).stream()
                    .map(String::trim)
                    .map(nome -> nome.toLowerCase(Locale.ROOT))
                    .toList();

            if (colunas.stream().noneMatch(COLUNA_MAC::equals)) {
                System.err.printf(
                        "CSV '%s' ignorado: o cabeçalho precisa conter a coluna '%s'.%n",
                        chave, COLUNA_MAC);
                return 0;
            }

            // Passamos os nomes dinâmicos das colunas ao DAO. O banco retornará somente
            // as que existirem em componentes.nome_coluna, estiverem associadas ao perfil
            // do MAC e possuírem limite de atenção ou crítico configurado.
            List<String> colunasMetricas = colunas.stream()
                    .filter(nome -> !nome.isBlank())
                    .filter(nome -> !nome.equals(COLUNA_MAC))
                    .filter(nome -> !nome.equals(COLUNA_TIMESTAMP))
                    .distinct()
                    .toList();

            System.out.println("Colunas no CSV: " + String.join(", ", colunas));
            System.out.println("Colunas candidatas a métricas: " + String.join(", ", colunasMetricas));

            Map<String, Map<String, LimiteDao.Limite>> cachePorMac = new HashMap<>();
            int linhasLidas = 0;
            String linha;
            int numeroLinha = 1;

            while ((linha = reader.readLine()) != null) {
                numeroLinha++;
                if (linha.isBlank()) {
                    continue;
                }

                List<String> valores = parseCsvLine(linha);
                Map<String, String> registro = new LinkedHashMap<>();
                for (int i = 0; i < colunas.size(); i++) {
                    registro.put(colunas.get(i), i < valores.size() ? valores.get(i).trim() : "");
                }
                linhasLidas++;

                String mac = registro.get(COLUNA_MAC);
                if (mac == null || mac.isBlank()) {
                    System.err.printf("Linha %d do arquivo '%s' ignorada: endereco_mac vazio.%n",
                            numeroLinha, chave);
                    continue;
                }

                if (!cachePorMac.containsKey(mac)) {
                    try {
                        Map<String, LimiteDao.Limite> limites = dao.buscarPorMac(mac, colunasMetricas);
                        cachePorMac.put(mac, limites);
                        if (limites.isEmpty()) {
                            System.out.printf(
                                    "Nenhum limite correspondente às colunas deste CSV para o servidor %s.%n", mac);
                        }
                    } catch (SQLException e) {
                        System.err.printf("Erro ao buscar limites de %s: %s%n", mac, e.getMessage());
                        // Evita repetir a mesma consulta com falha para cada linha desse servidor.
                        cachePorMac.put(mac, Map.of());
                    }
                }

                Map<String, LimiteDao.Limite> limites = cachePorMac.get(mac);
                String timestamp = registro.getOrDefault(COLUNA_TIMESTAMP, "sem timestamp");

                for (LimiteDao.Limite limite : limites.values()) {
                    String bruto = registro.get(limite.nomeColuna().toLowerCase(Locale.ROOT));
                    if (bruto == null || bruto.isBlank()) {
                        continue;
                    }

                    double valor;
                    try {
                        valor = Double.parseDouble(bruto);
                    } catch (NumberFormatException e) {
                        // Métricas com valores não numéricos (ex.: estado de interface) não
                        // podem ser comparadas numericamente; ficam fora desta validação.
                        continue;
                    }

                    String nivel = null;
                    Double limiteAtingido = null;
                    if (atingiuLimite(valor, limite.critico(), limite.operador())) {
                        nivel = "CRITICO";
                        limiteAtingido = limite.critico();
                    } else if (atingiuLimite(valor, limite.atencao(), limite.operador())) {
                        nivel = "ATENCAO";
                        limiteAtingido = limite.atencao();
                    }

                    String chaveAlerta = mac + "|" + limite.nomeColuna() + "|" + nivel;
                    if (nivel != null && jaAlertados.add(chaveAlerta)) {
                        System.out.printf("[%s] %s | %s = %.2f %s (operador %s, limite %.2f) em %s%n",
                                nivel, mac, limite.nome(), valor, limite.unidade(),
                                limite.operador(), limiteAtingido, timestamp);
										if(nivel.equals("CRITICO") || nivel.equals("ATENCAO")) {
													String resumo = String.format(
								"[%s] %s - %s = %.2f %s (limite %.2f) em %s",
								nivel,mac,limite.nome(),valor,limite.unidade(),limiteAtingido,timestamp);
							criarTicket(jira,resumo);
						}
                    }
                }
            }

            System.out.printf("Linhas lidas em '%s': %d%n", chave, linhasLidas);
            return linhasLidas;
        }
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
