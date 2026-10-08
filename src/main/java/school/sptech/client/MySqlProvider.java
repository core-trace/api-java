package school.sptech.client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySqlProvider {

    private final String url;
    private final String usuario;
    private final String senha;

    public MySqlProvider() {
        String host  = System.getenv().getOrDefault("DB_HOST", "localhost");
        String porta = System.getenv().getOrDefault("DB_PORT", "3306");
        String banco = System.getenv().getOrDefault("DB_NAME", "coretrace");

        this.url = "jdbc:mysql://" + host + ":" + porta + "/" + banco
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        this.usuario = System.getenv("DB_USER");
        this.senha   = System.getenv("DB_PASSWORD");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, usuario, senha);
    }
}