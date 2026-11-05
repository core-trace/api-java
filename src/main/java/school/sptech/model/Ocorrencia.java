package school.sptech.model;

/**
 * Representa uma ocorrência identificada durante
 * a análise das métricas.
 *
 * Ainda não é um chamado do Jira.
 * É apenas o resultado de uma comparação com o banco.
 */
public record Ocorrencia(
        String mac,
        String recurso,
        String metrica,
        String campoOrigem,
        double valor,
        String nivel,
        String operador,
        double limite
) {}

