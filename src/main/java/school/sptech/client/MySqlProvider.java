package school.sptech.client;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySqlProvider {

    private final String url;
    private final String usuario;
    private final String senha;

    public MySqlProvider() {
        Dotenv env = Dotenv.load();
        String host = env.get("DB_HOST");
        String porta = env.get("DB_PORT", "3306");
        String banco = env.get("DB_NAME");

        this.url = "jdbc:mysql://" + host + ":" + porta + "/" + banco
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        this.usuario = env.get("DB_USER");
        this.senha = env.get("DB_PASSWORD");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, usuario, senha);
    }
}
