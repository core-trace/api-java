package school.sptech.service;

import school.sptech.model.Ocorrencia;
import school.sptech.config.Env;
import school.sptech.config.Jira;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class ChamadoService {

    /**
     * Verifica se existe um chamado para o mesmo problema.
     * Se não existir, cria um novo no Jira.
     *
     * Os logs ajudam a verificar se a etiqueta e o título
     * estão sendo gerados corretamente.
     */
    public static void criarTicket(
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
}
