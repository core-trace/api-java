package school.sptech.config;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Carrega variáveis do ambiente do sistema e do arquivo .env.
 * Variáveis já definidas no sistema operacional têm precedência.
 */
public final class Env {

    private static final Dotenv DOTENV = Dotenv.configure()
            .directory(System.getProperty("user.dir"))
            .ignoreIfMissing()
            .load();

    private Env() {
        // Classe utilitária: não deve ser instanciada.
    }

    public static String get(String nome, String valorPadrao) {
        String valor = DOTENV.get(nome);
        return valor == null || valor.isBlank() ? valorPadrao : valor.trim();
    }

    public static String require(String nome) {
        String valor = DOTENV.get(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Variável obrigatória não configurada: " + nome
                            + ". Confira o arquivo .env e o diretório de execução.");
        }
        return valor.trim();
    }
}
