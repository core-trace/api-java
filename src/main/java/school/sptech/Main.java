package school.sptech;

import io.github.cdimascio.dotenv.Dotenv;
import school.sptech.client.MySqlProvider;
import school.sptech.client.S3Provider;
import school.sptech.config.Jira;
import school.sptech.docker.LimiteDao;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {

    // métricas do CSV (mesmo nome de nome_coluna no banco)
    private static final List<String> METRICAS = List.of(
            "cpu_percent", "ram_percent", "disk_percent", "swap_percent");

    public static void main(String[] args) {
        try {
            Dotenv env = Dotenv.load();
            validarConfiguracao();

            S3Client s3Client = new S3Provider().getS3Client();
            String bucketName = env.get("S3_BUCKET");
            String key = env.get("S3_KEY");

            List<Map<String, String>> registros = lerCsv(s3Client, bucketName, key);
            System.out.println("Linhas lidas: " + registros.size());

            validarLimites(registros, new LimiteDao(new MySqlProvider()), criarJira());

        } catch (S3Exception e) {
            String mensagem = e.awsErrorDetails() == null
                    ? e.getMessage()
                    : e.awsErrorDetails().errorMessage();
            System.err.println("Erro ao acessar o S3: " + mensagem);
        } catch (IOException e) {
            System.err.println("Erro ao ler o CSV: " + e.getMessage());
        } catch (IllegalStateException e) {
            System.err.println("Erro de configuração: " + e.getMessage());
        }
    }

    private static void validarConfiguracao() {

        Dotenv env = Dotenv.load();

        env.get("DB_HOST");
        env.get("DB_NAME");
        env.get("DB_USER");
        env.get("DB_PASSWORD");
        env.get("S3_BUCKET");
        env.get("S3_KEY");
        env.get("JIRA_BASE_URL");
        env.get("JIRA_EMAIL");
        env.get("JIRA_API_TOKEN");
        env.get("JIRA_PROJECT_KEY");
    }

    private static Jira criarJira() {
        Dotenv env = Dotenv.load();
        String url = env.get("JIRA_BASE_URL").replaceAll("/+$", "");
        String email = env.get("JIRA_EMAIL");
        String token = env.get("JIRA_API_TOKEN");
        return new Jira(url, email, token);
    }

    private static void validarLimites(List<Map<String, String>> registros, LimiteDao dao, Jira jira) {
        Map<String, Map<String, LimiteDao.Limite>> cache = new HashMap<>(); // MAC -> limites
        Set<String> jaAlertados = new HashSet<>(); // mac|metrica|nivel

        for (Map<String, String> registro : registros) {
            String mac = registro.get("endereco_mac");
            String timestamp = registro.get("timestamp");
            if (mac == null || mac.isBlank()) continue;

            Map<String, LimiteDao.Limite> limites = cache.get(mac);
            if (limites == null) {
                try {
                    limites = dao.buscarPorMac(mac, METRICAS); // 1 consulta por servidor
                } catch (SQLException e) {
                    System.err.println("Erro ao buscar limites de " + mac + ": " + e.getMessage());
                    limites = Map.of();
                }
                cache.put(mac, limites);
            }

            for (LimiteDao.Limite l : limites.values()) {
                String bruto = registro.get(l.nomeColuna());
                if (bruto == null || bruto.isBlank()) continue;

                double valor;
                try {
                    valor = Double.parseDouble(bruto);
                } catch (NumberFormatException e) {
                    continue;
                }

                String nivel = null;
                Double limiteAtingido = null;
                if (l.critico() != null && valor >= l.critico()) {
                    nivel = "CRITICO";
                    limiteAtingido = l.critico();
                } else if (l.atencao() != null && valor >= l.atencao()) {
                    nivel = "ATENCAO";
                    limiteAtingido = l.atencao();
                }

                if (nivel != null && jaAlertados.add(mac + "|" + l.nomeColuna() + "|" + nivel)) {
                    System.out.printf("[%s] %s | %s = %.2f%s (limite %.2f) em %s%n",
                            nivel, mac, l.nome(), valor, l.unidade(), limiteAtingido, timestamp);

                    if (nivel.equals("CRITICO")) {
                        String resumo = String.format("[CRITICO] %s - %s = %.2f%s (limite %.2f) em %s",
                                mac, l.nome(), valor, l.unidade(), limiteAtingido, timestamp);
                        criarTicket(jira, resumo);
                    }
                }
            }
        }
    }

    private static void criarTicket(Jira jira, String resumo) {
        try {
            Dotenv env = Dotenv.load();
            String resposta = jira.createIssue(env.get("JIRA_PROJECT_KEY"), resumo, env.get("JIRA_ISSUE_TYPE", "Task"));
            System.out.println("  -> Ticket criado: " + resposta);
        } catch (IOException e) {
            System.err.println("  -> Erro de rede ao criar ticket: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("  -> Criação do ticket interrompida.");
        } catch (RuntimeException e) {
            // o Jira.java lança RuntimeException quando o status não é 2xx
            System.err.println("  -> Jira recusou o ticket: " + e.getMessage());
        }
    }

    private static List<Map<String, String>> lerCsv(S3Client s3, String bucket, String key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        List<Map<String, String>> registros = new ArrayList<>();

        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(request);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String cabecalho = reader.readLine();
            if (cabecalho == null) return registros;

            String[] colunas = cabecalho.replace("\uFEFF", "").split(",", -1);

            String linha;
            while ((linha = reader.readLine()) != null) {
                if (linha.isBlank()) continue;

                String[] valores = linha.split(",", -1);
                Map<String, String> registro = new LinkedHashMap<>();
                for (int i = 0; i < colunas.length; i++) {
                    registro.put(colunas[i].trim(), i < valores.length ? valores[i].trim() : "");
                }
                registros.add(registro);
            }
        }

        return registros;
    }
}