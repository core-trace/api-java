package school.sptech.docker;

import school.sptech.client.MySqlProvider;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;

/** Acesso aos limites cadastrados para as métricas habilitadas no perfil do servidor. */
public class LimiteDao {

    public record Limite(String nomeColuna, String nome, String unidade,
                         Double atencao, Double critico, String operador) {}

    private final MySqlProvider provider;

    public LimiteDao(MySqlProvider provider) {
        this.provider = provider;
    }

    /**
     * Busca os limites configurados para determinadas métricas
     * de uma máquina, identificada pelo endereço MAC.
     *
     * A consulta considera:
     * - O perfil associado ao servidor;
     * - As métricas cadastradas nesse perfil;
     * - Apenas componentes que possuam pelo menos um limite.
     *
     * Exemplo:
     * MAC: 02:00:00:00:00:01
     * Métricas: ["cpu_percent", "ram_percent"]
     *
     * Retorno:
     * Map<String, Limite>
     * Chave: identificador da métrica em minúsculas
     * Valor: limites e operador configurados no banco
     */
    public Map<String, Limite> buscarPorMac(
            String mac,
            Collection<String> nomesMetricas
    ) throws SQLException {

        Map<String, Limite> limites = new HashMap<>();

        // Sem MAC ou métricas, não existe uma consulta válida.
        if (mac == null || mac.isBlank()
                || nomesMetricas == null || nomesMetricas.isEmpty()) {
            return limites;
        }

        // Normaliza os identificadores das métricas.
        // Exemplo: "CPU_PERCENT" -> "cpu_percent"
        //
        // distinct() remove duplicações para que a mesma
        // métrica não apareça várias vezes no IN do SQL.
        List<String> metricas = nomesMetricas.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(nome -> nome.toLowerCase(Locale.ROOT))
                .filter(nome -> !nome.isBlank())
                .distinct()
                .toList();

        if (metricas.isEmpty())
            return limites;

        // Gera um placeholder (?) para cada métrica recebida.
        //
        // Exemplo:
        // 1 métrica  -> ?
        // 3 métricas -> ?, ?, ?
        String placeholders = metricas.stream()
                .map(ignorado -> "?")
                .collect(Collectors.joining(", "));

        String sql = """
        SELECT DISTINCT
               comp.nome,
               comp.nome_coluna,
               comp.unidade,
               cp.limite_atencao,
               cp.limite_critico,
               cp.operador
        FROM servidores s
        JOIN componentes_perfil cp
          ON cp.fk_perfil_servidor = s.fk_perfil_servidor
        JOIN componentes comp
          ON comp.id_componente = cp.fk_componente
        WHERE s.endereco_mac = ?
          AND LOWER(comp.nome_coluna) IN (%s)
          AND (
              cp.limite_atencao IS NOT NULL
              OR cp.limite_critico IS NOT NULL
          )
        """.formatted(placeholders);

        // Abre a conexão e prepara o SQL.
        // O try-with-resources fecha os recursos automaticamente.
        try (
                Connection conn = provider.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)
        ) {

            // O primeiro parâmetro do SQL é o MAC.
            int indice = 1;
            ps.setString(indice++, mac.trim());

            // Preenche os demais parâmetros com as métricas.
            //
            // Exemplo:
            // parâmetro 2 -> cpu_percent
            // parâmetro 3 -> ram_percent
            for (String metrica : metricas) {
                ps.setString(indice++, metrica);
            }

            // Executa a consulta e percorre os resultados.
            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    String nomeColuna = rs.getString("nome_coluna");

                    // Cria o objeto Limite com os valores do banco
                    // e guarda no Map usando a métrica como chave.
                    limites.put(
                            nomeColuna.toLowerCase(Locale.ROOT),
                            new Limite(
                                    nomeColuna,
                                    rs.getString("nome"),
                                    rs.getString("unidade"),

                                    // Converte BigDecimal para Double,
                                    // preservando null quando não houver limite.
                                    paraDouble(rs.getBigDecimal("limite_atencao")),
                                    paraDouble(rs.getBigDecimal("limite_critico")),

                                    rs.getString("operador")
                            )
                    );
                }
            }
        }

        return limites;
    }

    private static Double paraDouble(BigDecimal valor) {
        return valor == null ? null : valor.doubleValue();
    }
}
