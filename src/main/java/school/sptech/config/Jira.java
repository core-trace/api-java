package school.sptech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import school.sptech.dto.IssueFieldsDto;
import school.sptech.dto.IssueRequestDto;
import school.sptech.dto.IssueTypeDto;
import school.sptech.dto.ProjectDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Jira {

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Jira(String baseUrl, String email, String apiToken) {
        this.baseUrl = baseUrl;

        String auth = email + ":" + apiToken;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        this.httpClient = HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(30))
              .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Mantém compatibilidade com chamadas antigas
     * que não utilizam etiquetas.
     */
    public String createIssue(
            String projectKey,
            String summary,
            String issueType
    ) throws IOException, InterruptedException {

        return createIssue(projectKey, summary, issueType, null);
    }

    /**
     * Cria um chamado no Jira com uma etiqueta opcional.
     *
     * A etiqueta identifica o problema independentemente
     * do valor da métrica ou do lote processado.
     *
     * Exemplo:
     * CPU lógica 1 = 80% -> coretrace-abc123
     * CPU lógica 1 = 95% -> coretrace-abc123
     */
    public String createIssue(
            String projectKey,
            String summary,
            String issueType,
            String etiqueta
    ) throws IOException, InterruptedException {

        // Aproveita os DTOs existentes.
        ProjectDto project = new ProjectDto(projectKey);
        IssueTypeDto type = new IssueTypeDto(issueType);

        IssueFieldsDto fields = new IssueFieldsDto(
                project, summary, type
        );

        IssueRequestDto issueRequest = new IssueRequestDto(fields);

        // Converte os DTOs para uma árvore JSON editável.
        // Assim não precisamos alterar IssueFieldsDto.
        ObjectNode json = objectMapper.valueToTree(issueRequest);

        // Acrescenta a etiqueta dentro de "fields".
        if (etiqueta != null && !etiqueta.isBlank()) {

            JsonNode fieldsNode = json.path("fields");

            if (!(fieldsNode instanceof ObjectNode campos)) {
                throw new IOException("Estrutura fields inválida");
            }

            campos.putArray("labels").add(etiqueta);
        }

        // Converte o JSON completo em String.
        String corpo = objectMapper.writeValueAsString(json);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/issue"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            return response.body();
        }

        throw new IOException(
                "Erro ao criar chamado no Jira: HTTP "
                        + status + " - " + response.body()
        );
    }

    /**
     * Busca um chamado não concluído que possua
     * a etiqueta correspondente a um problema.
     *
     * @param projectKey Chave do projeto Jira.
     * @param etiqueta Identificador estável do problema.
     *
     * @return Chave do chamado encontrado (ex.: CT-15)
     *         ou null quando não existir.
     *
     * Erros de consulta nunca são tratados como
     * ausência de chamado.
     */
    public String buscarChamadoAberto(
            String projectKey,
            String etiqueta
    ) throws IOException {

        // Consulta JQL para localizar um chamado
        // que ainda não esteja na categoria "Done".
        String jql = String.format(
                "project = \"%s\" AND labels = \"%s\" " +
                        "AND statusCategory != Done",
                projectKey,
                etiqueta
        );

        // Monta o corpo da busca utilizando o ObjectMapper.
        // Precisamos apenas de um resultado.
        ObjectNode corpo = objectMapper.createObjectNode();

        corpo.put("jql", jql);
        corpo.put("maxResults", 1);
        corpo.putArray("fields").add("status");

        String json = objectMapper.writeValueAsString(corpo);

        // Utilizamos o mesmo HttpClient e a mesma
        // autenticação já configurados no construtor.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/search/jql"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

        } catch (InterruptedException e) {

            // Preserva o estado de interrupção da thread.
            Thread.currentThread().interrupt();

            throw new IOException(
                    "Busca de chamados interrompida", e
            );
        }

        // Se a consulta falhar, não podemos assumir
        // que o chamado não existe.
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Erro ao consultar Jira: HTTP "
                            + response.statusCode()
                            + " - " + response.body()
            );
        }

        // Interpreta a resposta da API.
        JsonNode resultado = objectMapper.readTree(response.body());

        JsonNode issues = resultado.path("issues");

        if (!issues.isArray()) {
            throw new IOException(
                    "Resposta do Jira sem array issues"
            );
        }

        // Nenhum chamado não concluído encontrado.
        if (issues.isEmpty()) {
            return null;
        }

        // Obtém a chave do primeiro chamado encontrado.
        String chave = issues.get(0).path("key").asText(null);

        if (chave == null || chave.isBlank()) {
            throw new IOException("Chamado sem chave na resposta Jira");
        }

        return chave;
    }

    /**
     * Atualiza um chamado existente quando sua gravidade
     * evolui de ATENCAO para CRITICO.
     *
     * O método:
     * 1. Consulta o chamado atual.
     * 2. Verifica se já está classificado como crítico.
     * 3. Atualiza o título e acrescenta uma etiqueta.
     *
     * Não altera o status do chamado nem o encerra.
     *
     * @return true se houve atualização;
     *         false se já estava atualizado.
     */
    public boolean atualizarChamadoParaCritico(
            String chaveChamado,
            String novoTitulo
    ) throws IOException {

        String url = baseUrl + "/rest/api/3/issue/" + chaveChamado;

        // 1. Consulta o título e as etiquetas atuais.
        HttpRequest consulta = HttpRequest.newBuilder()
                .uri(URI.create(url + "?fields=summary,labels"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resposta;

        try {
            resposta = httpClient.send(
                    consulta,
                    HttpResponse.BodyHandlers.ofString()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Consulta ao Jira interrompida", e);
        }

        if (resposta.statusCode() != 200) {
            throw new IOException(
                    "Erro ao consultar chamado: HTTP "
                            + resposta.statusCode()
                            + " - " + resposta.body()
            );
        }

        // 2. Interpreta os dados retornados pelo Jira.
        JsonNode campos = objectMapper
                .readTree(resposta.body())
                .path("fields");

        if (!campos.isObject()) {
            throw new IOException("Resposta Jira sem campos válidos");
        }

        String tituloAtual = campos.path("summary").asText("");

        // Reconhece tanto CRITICO quanto CRÍTICO.
        boolean tituloJaCritico =
                tituloAtual.startsWith("[CRÍTICO]")
                        || tituloAtual.startsWith("[CRITICO]");

        // Verifica se já existe a etiqueta de gravidade.
        boolean etiquetaJaCritica = false;

        JsonNode etiquetas = campos.path("labels");

        if (etiquetas.isArray()) {
            for (JsonNode etiqueta : etiquetas) {
                if ("coretrace-critico".equals(etiqueta.asText())) {
                    etiquetaJaCritica = true;
                    break;
                }
            }
        }

        // Se o título e a etiqueta já indicam crítico,
        // não precisamos enviar outra atualização.
        if (tituloJaCritico && etiquetaJaCritica) {
            return false;
        }

        // 3. Monta somente os campos que precisam mudar.
        ObjectNode corpo = objectMapper.createObjectNode();

        if (!tituloJaCritico) {
            corpo.putObject("fields")
                    .put("summary", novoTitulo);
        }

        if (!etiquetaJaCritica) {

            // A operação add preserva as etiquetas existentes,
            // inclusive a etiqueta única do CoreTrace.
            corpo.putObject("update")
                    .putArray("labels")
                    .addObject()
                    .put("add", "coretrace-critico");
        }

        // 4. Envia a atualização ao Jira.
        HttpRequest atualizacao = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(corpo)
                ))
                .build();

        try {
            resposta = httpClient.send(
                    atualizacao,
                    HttpResponse.BodyHandlers.ofString()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Atualização do Jira interrompida", e);
        }

        // O Jira normalmente responde 204 quando
        // a edição é concluída com sucesso.
        if (resposta.statusCode() < 200
                || resposta.statusCode() >= 300) {

            throw new IOException(
                    "Erro ao atualizar chamado: HTTP "
                            + resposta.statusCode()
                            + " - " + resposta.body()
            );
        }

        return true;
    }
}
