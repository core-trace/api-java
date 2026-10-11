package school.sptech.docker;

import school.sptech.client.MySqlProvider;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LimiteDao {

    public record Limite(String nomeColuna, String nome, String unidade,
                         Double atencao, Double critico) {}

    private final MySqlProvider provider;

    public LimiteDao(MySqlProvider provider) {
        this.provider = provider;
    }

    /**
     * Retorna nome_coluna -> Limite do servidor (identificado pelo MAC),
     * apenas para as métricas pedidas e que tenham limite definido.
     */
    public Map<String, Limite> buscarPorMac(String mac, Collection<String> nomesColunas) throws SQLException {
        Map<String, Limite> limites = new HashMap<>();
        if (nomesColunas == null || nomesColunas.isEmpty()) return limites;

        String placeholders = String.join(", ", Collections.nCopies(nomesColunas.size(), "?"));

        String sql = """
            SELECT DISTINCT comp.nome, comp.nome_coluna, comp.unidade,
                   cp.limite_atencao, cp.limite_critico
            FROM servidores s
            JOIN componentes_perfil cp ON cp.fk_perfil_servidor = s.fk_perfil_servidor
            JOIN componentes comp ON comp.id_componente = cp.fk_componente
            WHERE s.endereco_mac = ?
              AND comp.nome_coluna IN (%s)
              AND (cp.limite_atencao IS NOT NULL OR cp.limite_critico IS NOT NULL)
            """.formatted(placeholders);

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int i = 1;
            ps.setString(i++, mac);
            for (String coluna : nomesColunas) {
                ps.setString(i++, coluna);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    limites.put(rs.getString("nome_coluna"), new Limite(
                            rs.getString("nome_coluna"),
                            rs.getString("nome"),
                            rs.getString("unidade"),
                            paraDouble(rs.getBigDecimal("limite_atencao")),
                            paraDouble(rs.getBigDecimal("limite_critico"))));
                }
            }
        }
        return limites;
    }

    /** Atalho: busca os limites das 4 métricas padrão. */
    public Map<String, Limite> buscarPorMac(String mac) throws SQLException {
        return buscarPorMac(mac, List.of("cpu_percent", "ram_percent", "disk_percent", "swap_percent"));
    }

    private static Double paraDouble(BigDecimal valor) {
        return valor == null ? null : valor.doubleValue();
    }
}
