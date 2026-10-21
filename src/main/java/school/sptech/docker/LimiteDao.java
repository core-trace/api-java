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
     * Retorna nome_coluna -> Limite para um MAC específico.
     *
     * A coleção recebida vem do cabeçalho do CSV atual. Assim, a consulta é dinâmica:
     * só são considerados nomes que aparecem no arquivo, existem em componentes.nome_coluna,
     * estão associados ao perfil do servidor e têm pelo menos um limite definido.
     */
    public Map<String, Limite> buscarPorMac(String mac, Collection<String> nomesColunas) throws SQLException {
        Map<String, Limite> limites = new HashMap<>();
        if (mac == null || mac.isBlank() || nomesColunas == null || nomesColunas.isEmpty()) {
            return limites;
        }

        List<String> colunas = nomesColunas.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(nome -> nome.toLowerCase(Locale.ROOT))
                .filter(nome -> !nome.isBlank())
                .distinct()
                .toList();

        if (colunas.isEmpty()) {
            return limites;
        }

        String placeholders = colunas.stream()
                .map(ignorado -> "?")
                .collect(Collectors.joining(", "));

        String sql = """
            SELECT DISTINCT comp.nome,
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
              AND (cp.limite_atencao IS NOT NULL OR cp.limite_critico IS NOT NULL)
            """.formatted(placeholders);

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int indice = 1;
            ps.setString(indice++, mac.trim());
            for (String coluna : colunas) {
                ps.setString(indice++, coluna);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nomeColuna = rs.getString("nome_coluna");
                    limites.put(nomeColuna.toLowerCase(Locale.ROOT), new Limite(
                            nomeColuna,
                            rs.getString("nome"),
                            rs.getString("unidade"),
                            paraDouble(rs.getBigDecimal("limite_atencao")),
                            paraDouble(rs.getBigDecimal("limite_critico")),
                            rs.getString("operador")));
                }
            }
        }

        return limites;
    }

    private static Double paraDouble(BigDecimal valor) {
        return valor == null ? null : valor.doubleValue();
    }
}
