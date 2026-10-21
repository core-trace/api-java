package school.sptech.client;

import school.sptech.config.Env;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySqlProvider {

    private final String url;
    private final String usuario;
    private final String senha;

    public MySqlProvider() {
        String host = Env.require("DB_HOST");
        String porta = Env.get("DB_PORT", "3306");
        String banco = Env.require("DB_NAME");

        this.url = "jdbc:mysql://" + host + ":" + porta + "/" + banco
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        this.usuario = Env.require("DB_USER");
        this.senha = Env.require("DB_PASSWORD");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, usuario, senha);
    }
}
